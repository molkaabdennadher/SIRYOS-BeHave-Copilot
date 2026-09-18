package com.siryos.behave.chatbot.history;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * NOUVEAU (historique des conversations). Un tour de conversation
 * persisté (question utilisateur OU réponse assistant — deux lignes par
 * échange, comme ChatGPT stocke ses "messages").
 *
 * Nommé ChatMessageEntity (et pas ChatMessage) pour ne pas entrer en
 * conflit avec un éventuel type "ChatMessage" de Spring AI dans le même
 * classpath.
 */
@Entity
@Table(name = "chat_messages")
public class ChatMessageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false)
    private ChatSession session;

    /** "user" ou "assistant". */
    @Column(nullable = false)
    private String role;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    /** SIMPLE / DETAILLE / TECHNIQUE — null pour un message utilisateur. */
    private String explanationLevel;

    @Column(nullable = false)
    private Instant createdAt;

    protected ChatMessageEntity() {
        // requis par JPA
    }

    public ChatMessageEntity(ChatSession session, String role, String content, String explanationLevel) {
        this.session = session;
        this.role = role;
        this.content = content;
        this.explanationLevel = explanationLevel;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public ChatSession getSession() { return session; }
    public String getRole() { return role; }
    public String getContent() { return content; }
    public String getExplanationLevel() { return explanationLevel; }
    public Instant getCreatedAt() { return createdAt; }
}
