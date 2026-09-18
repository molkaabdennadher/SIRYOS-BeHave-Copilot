package com.siryos.behave.chatbot.chat;

import com.siryos.behave.chatbot.chat.dto.ChatRequest;
import com.siryos.behave.chatbot.chat.dto.ChatRequest.HistoryTurn;
import com.siryos.behave.chatbot.chat.dto.ChatResponse;
import com.siryos.behave.chatbot.history.ChatHistoryService;
import com.siryos.behave.chatbot.history.ChatSession;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/chat")
@CrossOrigin(origins = "http://localhost:4200") // widget Angular
public class ChatController {

    private final ChatService chatService;
    private final ChatHistoryService chatHistoryService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ChatController(ChatService chatService, ChatHistoryService chatHistoryService) {
        this.chatService = chatService;
        this.chatHistoryService = chatHistoryService;
    }

    /**
     * MODIFIÉ (historique des conversations) : résout/crée la session AVANT
     * d'appeler le RAG, pour que ChatService reçoive déjà l'id définitif
     * (utile pour ConversationLanguageStore), puis persiste la question et
     * la réponse une fois obtenue. La réponse renvoyée porte toujours le
     * sessionId réel (cf. ChatResponse) — le frontend doit s'en resservir
     * pour les messages suivants, même s'il en avait envoyé un autre/aucun.
     */
    @PostMapping
    public ChatResponse chat(@RequestBody ChatRequest request) {
        ChatSession session = chatHistoryService.getOrCreateSession(request.sessionId(), null);
        ChatRequest resolvedRequest = new ChatRequest(request.question(), request.level(),
                session.getId().toString(), request.history());

        ChatResponse response = chatService.answer(resolvedRequest);

        chatHistoryService.appendExchange(session, request.question(), response.answer(),
                request.level() != null ? request.level().name() : null);

        return new ChatResponse(response.answer(), response.sources(), response.language(),
                response.images(), session.getId().toString());
    }

    /**
     * MODIFIÉ : accepte désormais un champ multipart optionnel `history`
     * (JSON sérialisé d'une liste de HistoryTurn), pour que les questions
     * de suivi sur une image ("et le bouton en haut à droite ?") gardent
     * le contexte de la conversation (cf. §22, Test 5). Même logique de
     * résolution de session + persistance que /api/chat (voir ci-dessus).
     */
    @PostMapping(value = "/image", consumes = "multipart/form-data")
    public ChatResponse chatWithImage(
            @RequestParam("image") MultipartFile image,
            @RequestParam(value = "question", required = false) String question,
            @RequestParam(value = "level", required = false) ChatRequest.ExplanationLevel level,
            @RequestParam(value = "sessionId", required = false) String sessionId,
            @RequestParam(value = "history", required = false) String historyJson) {
        List<HistoryTurn> history = parseHistory(historyJson);

        ChatSession session = chatHistoryService.getOrCreateSession(sessionId, null);
        String resolvedSessionId = session.getId().toString();

        ChatResponse response = chatService.answerWithImage(image, question, level, resolvedSessionId, history);

        String userText = (question == null || question.isBlank())
                ? "[image envoyée]"
                : question;
        chatHistoryService.appendExchange(session, userText, response.answer(),
                level != null ? level.name() : null);

        return new ChatResponse(response.answer(), response.sources(), response.language(),
                response.images(), resolvedSessionId);
    }

    private List<HistoryTurn> parseHistory(String historyJson) {
        if (historyJson == null || historyJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(historyJson, objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, HistoryTurn.class));
        } catch (Exception e) {
            // Un historique mal formé ne doit jamais faire échouer la
            // requête : on retombe simplement sur "pas d'historique".
            return List.of();
        }
    }
}