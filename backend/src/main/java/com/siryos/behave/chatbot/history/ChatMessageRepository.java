package com.siryos.behave.chatbot.history;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ChatMessageRepository extends JpaRepository<ChatMessageEntity, UUID> {
    List<ChatMessageEntity> findBySession_IdOrderByCreatedAtAsc(UUID sessionId);
    long countBySession_Id(UUID sessionId);
    void deleteBySession_Id(UUID sessionId);
}
