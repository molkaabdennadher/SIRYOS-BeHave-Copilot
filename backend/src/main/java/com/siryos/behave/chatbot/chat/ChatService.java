package com.siryos.behave.chatbot.chat;

import com.siryos.behave.chatbot.chat.LanguageDetector.Language;
import com.siryos.behave.chatbot.chat.dto.ChatRequest;
import com.siryos.behave.chatbot.chat.dto.ChatRequest.HistoryTurn;
import com.siryos.behave.chatbot.chat.dto.ChatResponse;
import com.siryos.behave.chatbot.chat.dto.ImageReference;
import com.siryos.behave.chatbot.chat.dto.SourceReference;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.document.Document;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.util.UriUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ChatService {

    private final VectorStore vectorStore;
    private final ChatClient chatClient;
    private final ChatModel chatModel;
    private final LanguageDetector languageDetector;
    private final ConversationLanguageStore languageStore;

    private static final int TOP_K = 8;
    private static final int MAX_SOURCES_SHOWN = 4;
    private static final int MAX_IMAGES_SHOWN = 4;
    private static final double SIMILARITY_THRESHOLD = 0.15;
    // NOUVEAU : seuil séparé, plus strict que SIMILARITY_THRESHOLD, pour
    // décider quels chunks méritent d'être montrés comme sources/images.
    // SIMILARITY_THRESHOLD (0.15) reste bas pour donner un maximum de
    // contexte textuel au LLM, mais un chunk à peine au-dessus de 0.15
    // n'est pas assez pertinent pour qu'on affiche son image comme
    // illustration de la réponse — sinon des images sans rapport
    // s'affichent dès que rien de bon n'a été trouvé (cf. cas "interface
    // de Predictive").
    private static final double DISPLAY_THRESHOLD = 0.5;
    private static final int EXCERPT_MAX_LENGTH = 220;

    // NOUVEAU : nombre maximum de tours d'historique réinjectés dans le
    // prompt (cf. demande §22 — contexte léger, pas toute la conversation).
    private static final int MAX_HISTORY_TURNS = 6;

    // Le modèle vision n'est pas une constante Java en dur — il est
    // configurable via application.yml (behave.ai.vision-model), cf.
    // demande §12 ("AIModelProvider"). Depuis la migration vers Gemini,
    // Gemini 3.7 Flash est multimodal (texte + image), supporte FR/EN/AR,
    // et est donc utilisé à la fois pour le chat texte ET la vision (voir
    // application.yml).
    @Value("${behave.ai.vision-model}")
    private String visionModel;

    private static final long MAX_IMAGE_UPLOAD_BYTES = 15L * 1024 * 1024; // marge sous la limite d'upload Gemini

    // MODIFIÉ (migration Groq -> Gemini) : le parsing manuel du message
    // d'erreur Groq ("Please try again in 23.4s") n'a plus lieu d'être —
    // l'API Gemini ne renvoie pas ce format, et ses quotas (même en tier
    // gratuit) sont nettement plus généreux que les 8000 TPM de Groq. On
    // garde un retry MANUEL simple (backoff fixe) uniquement en filet de
    // sécurité, en complément du retry déclaratif spring.ai.retry.* — qui
    // peut ne pas se déclencher de façon fiable selon la classification
    // Transient/NonTransient de l'exception (cf. issue spring-ai #846).
    private static final int MAX_RATE_LIMIT_RETRIES = 3;
    private static final long RATE_LIMIT_RETRY_DELAY_MILLIS = 5000;

    private <T> T callWithRateLimitRetry(java.util.function.Supplier<T> call) {
        int attempt = 0;
        while (true) {
            try {
                return call.get();
            } catch (NonTransientAiException e) {
                attempt++;
                boolean isRateLimit = e.getMessage() != null
                        && (e.getMessage().contains("RESOURCE_EXHAUSTED") || e.getMessage().contains("429"));
                if (!isRateLimit || attempt > MAX_RATE_LIMIT_RETRIES) {
                    throw e;
                }
                sleepQuietly(RATE_LIMIT_RETRY_DELAY_MILLIS * attempt);
            }
        }
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    public ChatService(VectorStore vectorStore,
                       ChatModel chatModel,
                       LanguageDetector languageDetector,
                       ConversationLanguageStore languageStore) {
        this.vectorStore = vectorStore;
        this.chatModel = chatModel;
        this.chatClient = ChatClient.builder(chatModel).build();
        this.languageDetector = languageDetector;
        this.languageStore = languageStore;
    }

    public ChatResponse answer(ChatRequest request) {
        Language language = resolveLanguage(request);
        if (isSmallTalk(request.question())) {
            return new ChatResponse(smallTalkAnswer(language), List.of(), language.name(), List.of(), request.sessionId());
        }
        RetrievalResult retrieval = retrieveRelevantChunks(request.question());

        String context = retrieval.chunks().stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n\n---\n\n"));

        String systemPrompt = buildSystemPrompt(request.level(), context, language, retrieval.lowConfidence());

        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt));
        messages.addAll(toHistoryMessages(request.history())); // NOUVEAU : contexte conversationnel
        messages.add(new UserMessage(request.question()));

        String rawAnswer = stripReasoning(callWithRateLimitRetry(() ->
                chatClient.prompt(new Prompt(messages)).call().content()));

        // SIMPLIFIÉ mais avec garde-fou : on affiche sources/images
        // uniquement pour les chunks dont le score dépasse DISPLAY_THRESHOLD
        // (plus strict que le seuil de retrieval). Le LLM continue de voir
        // TOUS les chunks retrouvés (y compris faibles) pour rédiger sa
        // réponse texte, mais seuls les chunks vraiment pertinents
        // illustrent la réponse avec des sources/images — sinon des
        // images sans rapport s'affichent quand rien de bon n'a été trouvé.
        List<Document> displayableChunks = retrieval.chunks().stream()
                .filter(d -> d.getScore() != null && d.getScore() >= DISPLAY_THRESHOLD)
                .toList();
        SourcesAndImages sourcesAndImages = buildSourcesAndImages(displayableChunks, request.question(), language);

        // NOUVEAU (historique) : sessionId toujours renvoyé — ChatController
        // a déjà résolu/créé la session avant d'appeler answer(), donc
        // request.sessionId() est ici garanti non-null.
        return new ChatResponse(rawAnswer, sourcesAndImages.sources(), language.name(), sourcesAndImages.images(), request.sessionId());
    }

    // ------------------------------------------------------------------
    // Historique conversationnel (NOUVEAU)
    // ------------------------------------------------------------------

    /**
     * Convertit l'historique envoyé par le frontend (texte seul, jamais
     * les images binaires précédentes — cf. §22) en messages Spring AI,
     * limités aux MAX_HISTORY_TURNS derniers tours.
     */
    private List<Message> toHistoryMessages(List<HistoryTurn> history) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        List<HistoryTurn> recent = history.size() > MAX_HISTORY_TURNS
                ? history.subList(history.size() - MAX_HISTORY_TURNS, history.size())
                : history;

        List<Message> result = new ArrayList<>();
        for (HistoryTurn turn : recent) {
            if (turn.text() == null || turn.text().isBlank()) continue;
            if ("assistant".equalsIgnoreCase(turn.role())) {
                result.add(new AssistantMessage(turn.text()));
            } else {
                result.add(new UserMessage(turn.text()));
            }
        }
        return result;
    }

    // ------------------------------------------------------------------
    // Vision + RAG (MODIFIÉ) : le chatbot reçoit une image et l'explique
    // en s'appuyant sur les guides indexés, pas uniquement sur le modèle
    // ------------------------------------------------------------------

    /**
     * MODIFIÉ (règle finale du cahier des charges : "Grounding >
     * Multimodalité > Génération").
     *
     * Avant, cette méthode appelait uniquement le modèle vision, sans
     * aucun lien avec les guides indexés. Ce n'est plus le cas : l'image
     * est maintenant analysée en 2 étapes (§10) :
     *
     *   1) Un premier appel vision "léger" fait décrire l'image par le
     *      modèle sous forme de concepts/mots-clés courts — cette
     *      description sert de REQUÊTE pour le RAG, comme une question texte.
     *   2) Cette requête interroge le vector store existant. Si des
     *      passages pertinents sont trouvés, un second appel vision — avec
     *      l'image ET le contexte documentaire retrouvé — génère la
     *      réponse finale, groundée dans les guides. Les mêmes sources et
     *      images de guide que le pipeline texte sont alors renvoyées.
     *   3) Si rien de pertinent n'est trouvé (lowConfidence), la réponse
     *      reste une pure description visuelle, avec une mention explicite
     *      qu'aucune correspondance n'a été trouvée (§26/§27).
     */
    public ChatResponse answerWithImage(MultipartFile image, String question,
                                        ChatRequest.ExplanationLevel level, String sessionId) {
        return answerWithImage(image, question, level, sessionId, List.of());
    }

    public ChatResponse answerWithImage(MultipartFile image, String question,
                                        ChatRequest.ExplanationLevel level, String sessionId,
                                        List<HistoryTurn> history) {
        if (image == null || image.isEmpty()) {
            throw new IllegalArgumentException("Aucune image fournie.");
        }
        if (image.getSize() > MAX_IMAGE_UPLOAD_BYTES) {
            throw new IllegalArgumentException("Image trop volumineuse (15 Mo max).");
        }

        String effectiveQuestion = (question == null || question.isBlank())
                ? "Décris cette image et explique ce qu'elle représente."
                : question;

        Language language = resolveLanguage(new ChatRequest(effectiveQuestion, level, sessionId, history));

        byte[] bytes;
        try {
            bytes = image.getBytes();
        } catch (IOException e) {
            throw new IllegalArgumentException("Image illisible : " + e.getMessage(), e);
        }

        MimeType mimeType = resolveImageMimeType(image.getContentType());
        Media media = new Media(mimeType, new ByteArrayResource(bytes));

        GoogleGenAiChatOptions visionOptions = GoogleGenAiChatOptions.builder()
                .model(visionModel)
                .temperature(0.3)
                .build();

        // --- Étape 1 : décrire l'image sous forme de requête RAG -------
        String searchQuery = extractSearchQueryFromImage(media, effectiveQuestion, visionOptions);


        // --- Étape 2 : retrieval RAG classique sur cette requête -------
        RetrievalResult retrieval = retrieveRelevantChunks(searchQuery);

        // SIMPLIFIÉ : plus de branche séparée "aucune correspondance" qui
        // coupait court avant de construire sources/images. On construit
        // toujours le contexte et les sources/images à partir de ce que le
        // retrieval a trouvé (peut être vide, buildSourcesAndImages gère
        // ce cas naturellement).
        String context = retrieval.chunks().stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n\n---\n\n"));

        String systemPrompt = buildImageSystemPrompt(level, language, context, retrieval.lowConfidence());
        UserMessage userMessage = UserMessage.builder().text(effectiveQuestion).media(media).build();
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt));
        messages.addAll(toHistoryMessages(history));
        messages.add(userMessage);

        String rawAnswer = stripReasoning(callWithRateLimitRetry(() -> chatModel.call(new Prompt(messages, visionOptions))
                .getResult().getOutput().getText()));

        SourcesAndImages sourcesAndImages = buildSourcesAndImages(
                retrieval.chunks().stream().filter(d -> d.getScore() != null && d.getScore() >= DISPLAY_THRESHOLD).toList(),
                searchQuery, language);
        // NOUVEAU (historique) : sessionId toujours renvoyé, résolu par ChatController.
        return new ChatResponse(rawAnswer, sourcesAndImages.sources(), language.name(), sourcesAndImages.images(), sessionId);
    }
    // Filet de sécurité conservé après la migration vers Gemini : Qwen
    // (thinking mode) renvoyait parfois son raisonnement interne entre
    // <think>...</think> directement dans le contenu. Gemini expose son
    // "thinking" séparément (pas dans le texte final), donc ce nettoyage
    // ne devrait plus rien retirer en pratique — on le garde par prudence,
    // au cas où un modèle Gemini "thinking" futur adopterait ce format.
    private static final java.util.regex.Pattern THINK_TAGS =
            java.util.regex.Pattern.compile("(?s)<think>.*?</think>\\s*");

    private String stripReasoning(String raw) {
        if (raw == null) return "";
        return THINK_TAGS.matcher(raw).replaceAll("").trim();
    }

    /**
     * Appel vision "léger" dont le seul but est de transformer l'image en
     * une courte requête texte exploitable par le retrieval existant. Le
     * modèle vision (Qwen 3.6 27B) lit déjà correctement le texte présent
     * dans les images : pas d'OCR séparé (cf. §11).
     */
    private String extractSearchQueryFromImage(Media media, String question, GoogleGenAiChatOptions visionOptions) {
        String systemPrompt = """
                Tu analyses une capture d'écran ou une image liée au produit BeHave
                (Predictive, Access ou Analytics), édité par Siryos.

                Réponds UNIQUEMENT par une courte liste de mots-clés/concepts (5 à 12
                mots maximum), séparés par des virgules, décrivant : le nom de l'écran
                ou de l'interface si identifiable, le module BeHave concerné, et tout
                texte lisible à l'écran (menus, titres, boutons). N'ajoute AUCUNE
                phrase, AUCUNE explication : uniquement les mots-clés.
                """;

        UserMessage userMessage = UserMessage.builder()
                .text("Question de l'utilisateur (contexte) : " + question)
                .media(media)
                .build();

        List<Message> messages = List.of(new SystemMessage(systemPrompt), userMessage);

        String keywords = callWithRateLimitRetry(() -> chatModel.call(new Prompt(messages, visionOptions))
                .getResult().getOutput().getText());

        return (keywords == null ? "" : keywords.trim()) + " " + question;
    }

    private MimeType resolveImageMimeType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return MimeTypeUtils.IMAGE_JPEG;
        }
        try {
            return MimeType.valueOf(contentType);
        } catch (Exception e) {
            return MimeTypeUtils.IMAGE_JPEG;
        }
    }

    private String buildImageSystemPrompt(ChatRequest.ExplanationLevel level, Language language,
                                          String context, boolean noGuideMatch) {
        String languageInstruction = languageInstruction(language);
        String styleInstruction = styleInstruction(level, language);

        if (noGuideMatch) {
            String noMatchNotice = switch (language) {
                case FR -> "Aucune information ou capture correspondante n'a été retrouvée dans les guides " +
                        "BeHave indexés pour cette image : décris-la du mieux possible à partir de ce que tu " +
                        "vois, mais indique clairement à l'utilisateur que cette réponse ne s'appuie sur AUCUNE " +
                        "documentation officielle.";
                case EN -> "No matching information was found in the indexed BeHave guides for this image: " +
                        "describe it as best you can from what you see, but clearly tell the user this answer " +
                        "is NOT based on any official documentation.";
                case AR -> "لم يتم العثور على معلومات مطابقة في أدلة BeHave المفهرسة لهذه الصورة: صفها قدر " +
                        "الإمكان بناءً على ما تراه، لكن أخبر المستخدم بوضوح أن هذه الإجابة لا تستند إلى أي وثيقة رسمية.";
            };
            return """
                    Tu es l'assistant IA de BeHave (Predictive, Access, Analytics), édité par Siryos.

                    L'utilisateur t'envoie une image. Décris ce qu'elle montre de façon claire et utile.
                    Ne prétends JAMAIS avoir trouvé cette interface dans un guide si ce n'est pas le cas.

                    %s
                    %s
                    %s
                    """.formatted(languageInstruction, styleInstruction, noMatchNotice);
        }

        return """
                Tu es l'assistant IA de BeHave (Predictive, Access, Analytics), édité par Siryos.

                L'utilisateur t'envoie une image (capture d'écran de l'application BeHave, tableau de
                bord, schéma...). Un extrait des guides BeHave officiels correspondant probablement à
                cette image t'est fourni ci-dessous : appuie-toi PRIORITAIREMENT sur ce contexte pour
                expliquer l'image (nom réel de l'écran/module, fonctionnement documenté), plutôt que sur
                tes seules connaissances générales. Si un détail visible sur l'image n'est pas couvert par
                le contexte, dis-le plutôt que d'inventer.

                %s
                %s

                IMPORTANT : n'ajoute AUCUNE citation entre crochets, AUCUN nom de fichier et AUCUNE
                section "Sources" dans ta réponse — cette partie est gérée séparément par l'application.

                Contexte documentaire (guides BeHave) :
                %s
                """.formatted(languageInstruction, styleInstruction, context);
    }

    // ------------------------------------------------------------------
    // Langue
    // ------------------------------------------------------------------

    private Language resolveLanguage(ChatRequest request) {
        Language detected = languageDetector.detect(request.question());
        String sessionId = request.sessionId();

        if (sessionId == null || sessionId.isBlank()) {
            return detected;
        }
        if (!languageDetector.isReliable(request.question())) {
            return languageStore.getOrDefault(sessionId, detected);
        }
        languageStore.set(sessionId, detected);
        return detected;
    }

    // ------------------------------------------------------------------
    // Retrieval
    // ------------------------------------------------------------------

    /**
     * SIMPLIFIÉ : un seul appel au vector store, avec un seuil bas
     * (SIMILARITY_THRESHOLD = 0.15) qui sert juste à écarter le bruit pur,
     * pas à décider si on montre ou non les sources/images. Tout chunk
     * retrouvé est traité comme une source potentielle ; "lowConfidence"
     * n'est plus qu'une indication pour le ton de la réponse texte
     * (avertissement dans le prompt), elle ne masque plus jamais les
     * sources ni les images du guide.
     */
    private RetrievalResult retrieveRelevantChunks(String question) {
        List<Document> chunks = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(question)
                        .topK(TOP_K)
                        .similarityThreshold(SIMILARITY_THRESHOLD)
                        .build()
        );
        return new RetrievalResult(chunks, chunks.isEmpty());
    }
    // NOUVEAU (bug : "bonjour" affichait une source/image sans rapport) :
    // un mot isolé et court comme une salutation produit un embedding peu
    // discriminant, qui peut par hasard dépasser DISPLAY_THRESHOLD sur un
    // chunk totalement hors sujet (cosine similarity peu fiable sur du
    // texte trop court/générique). Plutôt que de resserrer le seuil pour
    // tout le monde (ce qui masquerait aussi de vraies bonnes réponses),
    // on détecte les échanges de politesse en amont et on court-circuite
    // complètement le retrieval documentaire pour ceux-ci : pas de recherche
    // vectorielle, donc aucune chance qu'une source/image s'y accroche.
    // Volontairement conservateur (courte liste + longueur du message) :
    // mieux vaut rater une salutation formulée différemment que déclencher
    // ce court-circuit sur une vraie question métier.
    private static final java.util.regex.Pattern SMALL_TALK_PATTERN = java.util.regex.Pattern.compile(
            "(?i)^\\s*(bonjour|bonsoir|salut|coucou|hello|hi|hey|merci|thanks|thank you|" +
                    "au revoir|à bientôt|bye|goodbye|ok|okay|d'accord|مرحبا|أهلا|شكرا)\\s*[!.?]*\\s*$");

    private boolean isSmallTalk(String question) {
        if (question == null) return false;
        String trimmed = question.strip();
        // Un message un peu long a de bonnes chances de contenir une vraie
        // question même s'il commence par "bonjour" — on ne coupe le
        // retrieval que si le message ENTIER est une simple formule.
        return trimmed.length() <= 30 && SMALL_TALK_PATTERN.matcher(trimmed).matches();
    }

    private String smallTalkAnswer(Language language) {
        return switch (language) {
            case FR -> "Bonjour ! Je suis l'assistant BeHave. Posez-moi une question sur BeHave (Predictive, Access, Analytics) et je m'appuierai sur les guides pour vous répondre.";
            case EN -> "Hello! I'm the BeHave assistant. Ask me a question about BeHave (Predictive, Access, Analytics) and I'll use the guides to answer.";
            case AR -> "مرحباً! أنا مساعد BeHave. اطرح عليّ سؤالاً حول BeHave (Predictive وAccess وAnalytics) وسأعتمد على الأدلة للإجابة.";
        };
    }
    private record RetrievalResult(List<Document> chunks, boolean lowConfidence) {}

    // ------------------------------------------------------------------
    // Prompt
    // ------------------------------------------------------------------

    private String buildSystemPrompt(ChatRequest.ExplanationLevel level, String context, Language language, boolean lowConfidence) {
        String styleInstruction = styleInstruction(level, language);
        String languageInstruction = languageInstruction(language);
        String confidenceWarning = lowConfidence ? lowConfidenceInstruction(language) : "";

        return """
                Tu es l'assistant IA de BeHave (Predictive, Access, Analytics), édité par Siryos.

                %s

                Réponds UNIQUEMENT à partir du contexte documentaire fourni ci-dessous, ainsi que de
                l'historique de conversation ci-dessus si présent.
                Si l'information n'est pas dans le contexte fourni, dis-le clairement
                (dans la langue cible) au lieu d'inventer une réponse. N'utilise JAMAIS
                tes connaissances générales sur SAP ou un autre système pour compléter
                une réponse quand le contexte ne contient pas l'information : dis
                explicitement que ce n'est pas couvert par les guides indexés.
                %s
                %s

                IMPORTANT : n'ajoute AUCUNE citation entre crochets, AUCUN nom de
                fichier et AUCUNE section "Sources" dans ta réponse — cette partie
                est gérée séparément par l'application, en dehors de ton texte.
                Rédige uniquement une réponse claire et directe, sans note ni
                référence entre parenthèses ou crochets.

                Contexte documentaire :
                %s
                """.formatted(languageInstruction, styleInstruction, confidenceWarning, context);
    }

    private String lowConfidenceInstruction(Language language) {
        return switch (language) {
            case FR -> "ATTENTION : le contexte ci-dessous est de faible pertinence (aucun " +
                    "passage n'a été jugé suffisamment proche de la question). Il est très " +
                    "probable que la réponse ne s'y trouve PAS. Indique clairement à " +
                    "l'utilisateur que tu n'as pas trouvé cette information dans les guides " +
                    "BeHave indexés, et suggère-lui de reformuler sa question ou de vérifier " +
                    "l'orthographe des termes utilisés.";
            case EN -> "WARNING: the context below has low relevance (no passage was close " +
                    "enough to the question). The answer is likely NOT in it. Clearly tell " +
                    "the user you could not find this information in the indexed BeHave " +
                    "guides, and suggest rephrasing the question or checking spelling.";
            case AR -> "تنبيه: السياق أدناه ضعيف الصلة (لم يكن أي مقطع قريباً بما يكفي من " +
                    "السؤال). من المرجح أن الإجابة غير موجودة فيه. أخبر المستخدم بوضوح أنك " +
                    "لم تجد هذه المعلومة في أدلة BeHave المفهرسة، واقترح إعادة صياغة السؤال " +
                    "أو التحقق من الإملاء.";
        };
    }

    private String languageInstruction(Language language) {
        return switch (language) {
            case FR -> "CONSIGNE DE LANGUE : réponds strictement en FRANÇAIS.";
            case EN -> "LANGUAGE RULE: you must answer strictly in ENGLISH, even though these " +
                    "instructions are written in French. Do not default to French.";
            case AR -> "قاعدة اللغة: يجب أن تجيب حصراً باللغة العربية، حتى لو كانت هذه التعليمات مكتوبة بالفرنسية. لا تستخدم الفرنسية أبداً.";
        };
    }

    private String styleInstruction(ChatRequest.ExplanationLevel level, Language language) {
        ChatRequest.ExplanationLevel lvl = level != null ? level : ChatRequest.ExplanationLevel.DETAILLE;
        return switch (language) {
            case FR -> switch (lvl) {
                case SIMPLE -> "Réponds en langage clair, sans jargon SAP ni technique, pour un utilisateur métier.";
                case DETAILLE -> "Explique ton raisonnement, cite les KPIs et tableaux de bord concernés, pour un analyste.";
                case TECHNIQUE -> "Détaille les tables SAP, tcodes, objets d'autorisation et logs sources, pour un profil technique.";
            };
            case EN -> switch (lvl) {
                case SIMPLE -> "Answer in plain language, no SAP jargon, for a business user.";
                case DETAILLE -> "Explain your reasoning, mention the relevant KPIs and dashboards, for an analyst.";
                case TECHNIQUE -> "Detail the SAP tables, transaction codes, authorization objects and source logs, for a technical profile.";
            };
            case AR -> switch (lvl) {
                case SIMPLE -> "أجب بلغة واضحة وبسيطة دون مصطلحات SAP تقنية، موجهة لمستخدم أعمال.";
                case DETAILLE -> "اشرح منطقك واذكر مؤشرات الأداء ولوحات المعلومات المعنية، موجه لمحلل بيانات.";
                case TECHNIQUE -> "فصّل جداول SAP وأكواد المعاملات وكائنات التفويض وسجلات المصدر، موجه لملف تقني.";
            };
        };
    }

    // ------------------------------------------------------------------
    // Sources & Explainable AI
    // ------------------------------------------------------------------

    private record SourcesAndImages(List<SourceReference> sources, List<ImageReference> images) {}

    private SourcesAndImages buildSourcesAndImages(List<Document> chunks, String query, Language language) {
        LinkedHashMap<String, Document> deduped = new LinkedHashMap<>();
        for (Document doc : chunks) {
            String key = doc.getMetadata().get("documentId") + "#" + doc.getMetadata().get("page");
            deduped.putIfAbsent(key, doc);
        }

        List<Document> topDocs = deduped.values().stream()
                .limit(MAX_SOURCES_SHOWN)
                .toList();

        List<SourceReference> sources = topDocs.stream()
                .map(this::toSourceReference)
                .toList();

        Set<String> seenImageKeys = new LinkedHashSet<>();
        List<ImageReference> images = new ArrayList<>();
        for (Document doc : topDocs) {
            if (images.size() >= MAX_IMAGES_SHOWN) break;
            for (ImageReference ref : extractImageReferences(doc, seenImageKeys, language)) {
                images.add(ref);
                if (images.size() >= MAX_IMAGES_SHOWN) break;
            }
        }

        return new SourcesAndImages(sources, images);
    }

    @SuppressWarnings("unchecked")
    private List<ImageReference> extractImageReferences(Document doc, Set<String> seenKeys, Language language) {
        Map<String, Object> meta = doc.getMetadata();
        Object imagesObj = meta.get("images");
        if (!(imagesObj instanceof List<?> rawList) || rawList.isEmpty()) {
            return List.of();
        }

        String documentId = String.valueOf(meta.get("documentId"));
        String imageDocFolder = String.valueOf(meta.getOrDefault("imageDocFolder", ""));
        int page = parseInt(meta.get("page"));
        String section = meta.get("sectionTitle") != null ? String.valueOf(meta.get("sectionTitle")) : null;

        List<ImageReference> refs = new ArrayList<>();
        for (Object o : rawList) {
            String fileName = String.valueOf(o);
            String key = imageDocFolder + "/" + fileName;
            if (!seenKeys.add(key)) continue;

            String url = "/api/documents/image/"
                    + UriUtils.encodePathSegment(imageDocFolder, StandardCharsets.UTF_8)
                    + "/" + UriUtils.encodePathSegment(fileName, StandardCharsets.UTF_8);

            String explanation = buildImageExplanation(documentId, page, section, language);
            refs.add(new ImageReference(documentId, fileName, page, url, section, explanation));
        }
        return refs;
    }

    private String buildImageExplanation(String documentId, int page, String section, Language language) {
        return switch (language) {
            case FR -> section != null
                    ? "Cette image provient de la page %d du document « %s », section « %s », retrouvée comme résultat du retrieval documentaire.".formatted(page, documentId, section)
                    : "Cette image provient de la page %d du document « %s », retrouvée comme résultat du retrieval documentaire.".formatted(page, documentId);
            case EN -> section != null
                    ? "This image comes from page %d of \"%s\", section \"%s\", found as a documentary retrieval result.".formatted(page, documentId, section)
                    : "This image comes from page %d of \"%s\", found as a documentary retrieval result.".formatted(page, documentId);
            case AR -> section != null
                    ? "هذه الصورة من الصفحة %d من الوثيقة \"%s\"، القسم \"%s\"، تم العثور عليها كنتيجة للبحث في الوثائق.".formatted(page, documentId, section)
                    : "هذه الصورة من الصفحة %d من الوثيقة \"%s\"، تم العثور عليها كنتيجة للبحث في الوثائق.".formatted(page, documentId);
        };
    }

    private SourceReference toSourceReference(Document doc) {
        Map<String, Object> meta = doc.getMetadata();
        String documentId = String.valueOf(meta.get("documentId"));
        String fileName = String.valueOf(meta.get("fileName"));
        int page = parseInt(meta.get("page"));
        boolean viewablePage = Boolean.TRUE.equals(meta.get("viewablePage"));
        String pageLabel = viewablePage ? "Page " + page : String.valueOf(meta.getOrDefault("pageLabel", "N/A"));
        String section = meta.get("sectionTitle") != null ? String.valueOf(meta.get("sectionTitle")) : null;

        String encodedId = UriUtils.encodePathSegment(documentId, StandardCharsets.UTF_8);
        String sourceUrl = "/api/documents/file/" + encodedId + (viewablePage ? "#page=" + page : "");

        // NOUVEAU : score de similarité réel renvoyé par PGVector pour ce
        // chunk (cosine similarity, 0 à 1) — jamais un pourcentage inventé
        // par le LLM (cf. §17).
        double score = doc.getScore() != null ? doc.getScore() : 0.0;

        return new SourceReference(
                String.valueOf(meta.get("chunkId")),
                fileName,
                page,
                truncate(doc.getText(), EXCERPT_MAX_LENGTH),
                documentId,
                pageLabel,
                sourceUrl,
                viewablePage,
                section,
                score
        );
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        String clean = text.replaceAll("\\s+", " ").trim();
        if (clean.length() <= maxLen) return clean;
        int cut = clean.lastIndexOf(' ', maxLen);
        if (cut < 0) cut = maxLen;
        return clean.substring(0, cut) + "…";
    }

    private int parseInt(Object value) {
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return 0;
        }
    }
}