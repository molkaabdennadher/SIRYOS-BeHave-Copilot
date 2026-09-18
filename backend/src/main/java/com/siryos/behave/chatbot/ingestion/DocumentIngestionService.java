package com.siryos.behave.chatbot.ingestion;
import java.io.File;
import java.nio.charset.StandardCharsets;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.ExtractedTextFormatter;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
/**
 * RÉÉCRIT.
 *
 * PROBLÈME CORRIGÉ (retrieval qui rate des infos pourtant indexées) :
 * l'ancienne version indexait PHRASE PAR PHRASE (SentenceSplitter), ce qui
 * fragmente trop l'information : une phrase isolée, sortie de son
 * paragraphe, "matche" souvent mal sémantiquement avec la question posée,
 * même quand l'info est bien là. On indexe maintenant par CHUNKS de
 * paragraphe via TokenTextSplitter (bean déjà défini dans
 * VectorStoreConfig avec behave.chunk.size=800 / overlap=100, mais qui
 * n'était JAMAIS injecté ni appelé auparavant — c'était la cause racine).
 *
 * PROBLÈME CORRIGÉ (sources cliquables, y compris pour Word) :
 * chaque chunk conserve désormais : documentId (nom de fichier original),
 * fileName, page réelle quand disponible, et un indicateur viewablePage.
 * Pour les fichiers Word, on tente une conversion en PDF (WordToPdfConverter)
 * afin d'obtenir une pagination réelle et un lien "page=" fiable ; si la
 * conversion échoue, on retombe sur un simple numéro de section (pas de
 * numéro de page inventé).
 *
 * NOUVEAU (fonctionnalité "affiche la photo du guide") :
 * quand une pagination fiable est disponible (viewablePage=true, PDF natif
 * ou Word converti), on extrait aussi les images intégrées de chaque page
 * via ImageExtractionService, et on les attache aux chunks de cette page
 * (métadonnées "images" + "imageDocFolder"). ChatService s'en sert ensuite
 * pour renvoyer, en plus du texte, les images du guide pertinentes pour la
 * question posée.
 */
@Service
public class DocumentIngestionService {

    private final VectorStore vectorStore;
    private final TokenTextSplitter textSplitter;
    private final WordToPdfConverter wordToPdfConverter;
    private final ImageExtractionService imageExtractionService;

    @Value("${behave.docs.folder}")
    private String docsFolder;

    public DocumentIngestionService(VectorStore vectorStore,
                                    TokenTextSplitter textSplitter,
                                    WordToPdfConverter wordToPdfConverter,
                                    ImageExtractionService imageExtractionService) {
        this.vectorStore = vectorStore;
        this.textSplitter = textSplitter;
        this.wordToPdfConverter = wordToPdfConverter;
        this.imageExtractionService = imageExtractionService;
    }

    /**
     * NOUVEAU (espace admin) : ingestion d'UN SEUL fichier, déjà présent
     * sur disque (dossier behave.docs.storage-dir, alimenté par
     * GuideAdminService), au lieu du classpath. Retourne les chunks prêts
     * à être ajoutés au vector store — c'est à l'appelant (GuideAdminService)
     * de faire vectorStore.add(chunks), pour pouvoir d'abord logguer/
     * vérifier le résultat si besoin.
     *
     * Chaque Document renvoyé a un id STABLE (UUID dérivé du nom de
     * fichier + page + index de chunk, cf. chunkPages ci-dessous) : ces
     * ids sont ceux qu'il faut conserver (typiquement sur l'entité Guide)
     * pour pouvoir les supprimer plus tard via vectorStore.delete(ids)
     * quand un guide est modifié ou supprimé.
     */
    public List<Document> ingestFile(File file, String originalFilename) {
        return ingestOneDocument(new FileSystemResource(file), originalFilename);
    }

    public IngestionReport ingestAllDocuments() throws Exception {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources = resolver.getResources(docsFolder + "*.{pdf,docx,doc}");

        int fileCount = 0;
        int chunkCount = 0;
        List<String> skipped = new ArrayList<>();

        for (Resource resource : resources) {
            String originalFilename = resource.getFilename();
            try {
                List<Document> chunks = ingestOneDocument(resource, originalFilename);
                if (!chunks.isEmpty()) {
                    vectorStore.add(chunks);
                }
                fileCount++;
                chunkCount += chunks.size();
            } catch (Exception e) {
                // Un fichier corrompu/illisible ne doit pas bloquer
                // l'indexation des autres guides.
                skipped.add(originalFilename + " (" + e.getMessage() + ")");
            }
        }

        return new IngestionReport(fileCount, chunkCount, skipped);
    }

    private List<Document> ingestOneDocument(Resource resource, String originalFilename) {
        String lower = originalFilename == null ? "" : originalFilename.toLowerCase();

        if (lower.endsWith(".pdf")) {
            List<Document> pages = readPdfPages(resource);
            Map<Integer, List<String>> images = extractImagesSafely(resource, originalFilename);
            return chunkPages(pages, originalFilename, true, images);
        }

        // Word (.doc / .docx) : on essaie d'obtenir une vraie pagination
        // en convertissant en PDF.
        var convertedPdf = wordToPdfConverter.convertIfNeeded(resource);
        if (convertedPdf != null) {
            List<Document> pages = readPdfPages(new FileSystemResource(convertedPdf));
            Map<Integer, List<String>> images = imageExtractionService.extractImages(convertedPdf, sanitize(originalFilename));
            return chunkPages(pages, originalFilename, true, images);
        }

        // Repli : pas de conversion possible -> lecture Tika (pas de page
        // réelle). On numérote par "section" = index du chunk, jamais par
        // un faux numéro de page. Pas d'extraction d'images possible sans
        // pagination fiable.
        List<Document> raw = new TikaDocumentReader(resource).get();
        return chunkPages(raw, originalFilename, false, Map.of());
    }

    /**
     * Extraction d'images pour un PDF natif : nécessite un accès fichier
     * (resource.getFile()), ce qui suppose une exécution "exploded"
     * (mvn spring-boot:run / classes déployées sur disque), déjà requis
     * ailleurs dans ce service pour la même raison (WordToPdfConverter).
     */
    private Map<Integer, List<String>> extractImagesSafely(Resource resource, String originalFilename) {
        try {
            return imageExtractionService.extractImages(resource.getFile(), sanitize(originalFilename));
        } catch (Exception e) {
            return Map.of();
        }
    }

    private List<Document> readPdfPages(Resource resource) {
        PdfDocumentReaderConfig config = PdfDocumentReaderConfig.builder()
                .withPageExtractedTextFormatter(ExtractedTextFormatter.defaults())
                .withPagesPerDocument(1)
                .build();
        return new PagePdfDocumentReader(resource, config).get();
    }

    private List<Document> chunkPages(List<Document> pageDocuments, String originalFilename, boolean viewablePage,
                                      Map<Integer, List<String>> pageImages) {
        List<Document> chunks = new ArrayList<>();
        String sanitized = sanitize(originalFilename);
        int globalChunkIndex = 0;

        for (Document pageDoc : pageDocuments) {
            if (pageDoc.getText() == null || pageDoc.getText().isBlank()) continue;

            int page = viewablePage ? extractPageNumber(pageDoc) : (globalChunkIndex + 1);
            List<String> imagesOnThisPage = pageImages.getOrDefault(page, List.of());

            String cleanedText = cleanHeaderFooterNoise(pageDoc.getText());
            if (cleanedText.isBlank()) continue;
            Document cleanedPageDoc = new Document(cleanedText, pageDoc.getMetadata());

// CORRIGÉ : détection du titre de section sur le texte NETTOYÉ, pas le
// texte brut. Sur le texte brut, la première ligne est toujours l'en-tête
// générique du document ("DOCUMENT DE TRAVAIL Date : ...") — sectionTitle
// affichait donc cet en-tête au lieu du vrai titre ("IV. Corrective
// Actions Analysis") dans les sources/images renvoyées au client.
            String sectionTitle = detectSectionTitle(cleanedText);

            List<Document> pageChunks = textSplitter.apply(List.of(cleanedPageDoc));
            for (Document chunk : pageChunks) {
                String chunkId = "%s_p%d_c%d".formatted(sanitized, page, globalChunkIndex);

                Map<String, Object> metadata = new HashMap<>();
                metadata.put("documentId", originalFilename);
                metadata.put("fileName", originalFilename);
                metadata.put("page", page);
                metadata.put("chunkId", chunkId);
                metadata.put("viewablePage", viewablePage);
                if (sectionTitle != null) {
                    metadata.put("sectionTitle", sectionTitle);
                }
                if (!viewablePage) {
                    metadata.put("pageLabel", "Section " + page);
                }
                // NOUVEAU : images extraites de cette page du guide (vide
                // si aucune, ou si pas de pagination fiable).
                if (!imagesOnThisPage.isEmpty()) {
                    metadata.put("images", imagesOnThisPage);
                    metadata.put("imageDocFolder", sanitized);
                }

                String stableUuid = UUID.nameUUIDFromBytes(chunkId.getBytes(StandardCharsets.UTF_8)).toString();
                chunks.add(new Document(stableUuid, chunk.getText(), metadata));
                globalChunkIndex++;
            }
        }
        return chunks;
    }

    // PagePdfDocumentReader (avec withPagesPerDocument(1)) place le numéro
    // de page dans les métadonnées sous la clé "page_number" (1-based selon
    // PDFBox).
    private int extractPageNumber(Document doc) {
        Object pageMeta = doc.getMetadata().get("page_number");
        if (pageMeta == null) return 1;
        try {
            int page = Integer.parseInt(String.valueOf(pageMeta));
            return Math.max(page, 1);
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    // NOUVEAU : lignes d'en-tête/pied de page répétées sur (quasi) chaque
    // page des guides Siryos, identifiées par motif plutôt que par texte
    // exact (les valeurs comme la date ou le numéro de page changent).
    // On les retire ligne par ligne avant le chunking, sans toucher au
    // reste du contenu de la page.
    private static final java.util.regex.Pattern[] NOISE_LINE_PATTERNS = {
            java.util.regex.Pattern.compile("(?i)^\\s*DOCUMENT\\s+DE\\s+TRAVAIL\\b.*$"),
            java.util.regex.Pattern.compile("(?i)^\\s*GUIDE\\s+D[’'`]?UTILISATEUR\\s*$"),
            java.util.regex.Pattern.compile("(?i)^\\s*R[ée]f\\s*:\\s*DTR-DEL-\\d+/\\d+\\s*FR\\s*$"),
            java.util.regex.Pattern.compile("(?i)^\\s*Page\\s+\\d+\\s+sur\\s+\\d+\\s*$"),
            java.util.regex.Pattern.compile("(?i)^\\s*Date\\s*:\\s*\\d{2}-\\d{2}-\\d{2}\\s*$"),
            java.util.regex.Pattern.compile("(?i)^\\s*S\\s*I\\s*R\\s*Y\\s*O\\s*S\\s*$"),
    };

    private String cleanHeaderFooterNoise(String pageText) {
        if (pageText == null) return "";
        StringBuilder result = new StringBuilder();
        for (String line : pageText.split("\\R")) {
            boolean isNoise = false;
            for (java.util.regex.Pattern p : NOISE_LINE_PATTERNS) {
                if (p.matcher(line.strip()).matches()) {
                    isNoise = true;
                    break;
                }
            }
            if (!isNoise) {
                result.append(line).append("\n");
            }
        }
        return result.toString().strip();
    }

    private String sanitize(String filename) {
        if (filename == null) return "doc";
        int dot = filename.lastIndexOf('.');
        String base = dot > 0 ? filename.substring(0, dot) : filename;
        return base.replaceAll("[^a-zA-Z0-9]", "_");
    }
    // NOUVEAU (§5) : heuristique simple de détection de titre de section —
    // on prend la première ligne non vide de la page si elle ressemble à
    // un titre (courte, pas de ponctuation finale). Volontairement
    // conservateur : mieux vaut aucun titre détecté qu'un faux titre.
    private String detectSectionTitle(String pageText) {
        if (pageText == null) return null;
        for (String line : pageText.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) continue;
            boolean looksLikeTitle = trimmed.length() <= 90
                    && !trimmed.endsWith(".")
                    && !trimmed.endsWith(",")
                    && trimmed.split("\\s+").length <= 12;
            return looksLikeTitle ? trimmed : null;
        }
        return null;
    }
    public record IngestionReport(int filesIndexed, int chunksCreated, List<String> skippedFiles) {}
}