package com.siryos.behave.chatbot.history;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ChatSessionRepository extends JpaRepository<ChatSession, UUID> {
    List<ChatSession> findByUserIdOrderByUpdatedAtDesc(String userId);
    List<ChatSession> findByUserIdIsNullOrderByUpdatedAtDesc();
}
