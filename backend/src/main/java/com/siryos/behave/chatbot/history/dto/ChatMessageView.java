package com.siryos.behave.chatbot.history.dto;

import com.siryos.behave.chatbot.history.ChatMessageEntity;

import java.time.Instant;
import java.util.UUID;

public record ChatMessageView(UUID id, String role, String content, String explanationLevel, Instant createdAt) {
    public static ChatMessageView from(ChatMessageEntity m) {
        return new ChatMessageView(m.getId(), m.getRole(), m.getContent(), m.getExplanationLevel(), m.getCreatedAt());
    }
}
