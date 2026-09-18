package com.siryos.behave.chatbot.history;

import com.siryos.behave.chatbot.history.dto.ChatMessageView;
import com.siryos.behave.chatbot.history.dto.ChatSessionSummary;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * NOUVEAU (historique des conversations). Alimente la sidebar façon
 * ChatGPT côté Angular : liste des sessions, ouverture d'une session pour
 * revoir ce qui a été écrit, suppression.
 *
 * `userId` est un paramètre optionnel en attendant une vraie
 * authentification (cf. ChatSession) — laissez-le vide pour l'instant.
 */
@RestController
@RequestMapping("/api/chat/sessions")
@CrossOrigin(origins = "http://localhost:4200")
public class ChatHistoryController {

    private final ChatHistoryService chatHistoryService;

    public ChatHistoryController(ChatHistoryService chatHistoryService) {
        this.chatHistoryService = chatHistoryService;
    }

    @GetMapping
    public List<ChatSessionSummary> listSessions(@RequestParam(required = false) String userId) {
        return chatHistoryService.listSessions(userId);
    }

    /** Crée explicitement une nouvelle conversation vide (bouton "Nouvelle conversation"). */
    @PostMapping
    public ChatSessionSummary createSession(@RequestParam(required = false) String userId) {
        return ChatSessionSummary.from(chatHistoryService.getOrCreateSession(null, userId));
    }

    @GetMapping("/{id}/messages")
    public List<ChatMessageView> getMessages(@PathVariable UUID id) {
        return chatHistoryService.getMessages(id);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteSession(@PathVariable UUID id) {
        try {
            chatHistoryService.deleteSession(id);
            return ResponseEntity.noContent().build();
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        }
    }
}
