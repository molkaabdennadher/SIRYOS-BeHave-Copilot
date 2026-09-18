package com.siryos.behave.chatbot.history.dto;

import com.siryos.behave.chatbot.history.ChatSession;

import java.time.Instant;
import java.util.UUID;

public record ChatSessionSummary(UUID id, String title, Instant createdAt, Instant updatedAt) {
    public static ChatSessionSummary from(ChatSession s) {
        // Titre par défaut tant qu'aucun message n'a encore été échangé
        // (cf. ChatHistoryService#appendExchange, qui le fixe au premier message).
        String title = s.getTitle() != null ? s.getTitle() : "Nouvelle conversation";
        return new ChatSessionSummary(s.getId(), title, s.getCreatedAt(), s.getUpdatedAt());
    }
}
