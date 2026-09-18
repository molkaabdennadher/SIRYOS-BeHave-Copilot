package com.siryos.behave.chatbot.history;

import com.siryos.behave.chatbot.history.dto.ChatMessageView;
import com.siryos.behave.chatbot.history.dto.ChatSessionSummary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

/**
 * NOUVEAU (historique des conversations, façon ChatGPT).
 *
 * Résout/crée la session courante et persiste chaque échange
 * (question + réponse). Appelé depuis ChatController, jamais depuis
 * ChatService (qui reste une pure logique RAG, sans connaissance de la
 * persistance).
 */
@Service
public class ChatHistoryService {

    private static final int TITLE_MAX_LENGTH = 60;

    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;

    public ChatHistoryService(ChatSessionRepository sessionRepository, ChatMessageRepository messageRepository) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
    }

    /**
     * Si `rawSessionId` correspond à une session existante, la réutilise.
     * Sinon (absent, vide, mal formé, ou UUID inconnu — ex: id généré côté
     * client mais jamais persisté), en crée une nouvelle. Le contrôleur
     * doit renvoyer l'id RÉEL (session.getId()) au frontend dans la
     * réponse, pour que les messages suivants réutilisent la bonne valeur.
     */
    @Transactional
    public ChatSession getOrCreateSession(String rawSessionId, String userId) {
        if (rawSessionId != null && !rawSessionId.isBlank()) {
            try {
                UUID id = UUID.fromString(rawSessionId);
                Optional<ChatSession> found = sessionRepository.findById(id);
                if (found.isPresent()) {
                    return found.get();
                }
            } catch (IllegalArgumentException ignored) {
                // id mal formé (ancien format côté client) -> nouvelle session ci-dessous
            }
        }
        return sessionRepository.save(new ChatSession(userId));
    }

    @Transactional
    public void appendExchange(ChatSession session, String userText, String assistantText, String explanationLevel) {
        messageRepository.save(new ChatMessageEntity(session, "user", userText, null));
        messageRepository.save(new ChatMessageEntity(session, "assistant", assistantText, explanationLevel));
        if (session.getTitle() == null && userText != null && !userText.isBlank()) {
            session.setTitle(truncate(userText, TITLE_MAX_LENGTH));
        }
        session.touch();
        sessionRepository.save(session);
    }

    public List<ChatSessionSummary> listSessions(String userId) {
        List<ChatSession> sessions = (userId == null || userId.isBlank())
                ? sessionRepository.findByUserIdIsNullOrderByUpdatedAtDesc()
                : sessionRepository.findByUserIdOrderByUpdatedAtDesc(userId);
        return sessions.stream().map(ChatSessionSummary::from).toList();
    }
    @Transactional(readOnly = true)
    public List<ChatMessageView> getMessages(UUID sessionId) {
        return messageRepository.findBySession_IdOrderByCreatedAtAsc(sessionId)
                .stream().map(ChatMessageView::from).toList();
    }

    @Transactional
    public void deleteSession(UUID sessionId) {
        if (!sessionRepository.existsById(sessionId)) {
            throw new NoSuchElementException("Session introuvable : " + sessionId);
        }
        messageRepository.deleteBySession_Id(sessionId);
        sessionRepository.deleteById(sessionId);
    }

    private String truncate(String text, int maxLen) {
        String clean = text.replaceAll("\\s+", " ").trim();
        return clean.length() <= maxLen ? clean : clean.substring(0, maxLen) + "…";
    }
}
