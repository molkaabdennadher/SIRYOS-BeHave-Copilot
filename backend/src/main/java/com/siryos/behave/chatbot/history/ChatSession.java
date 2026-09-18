package com.siryos.behave.chatbot.history;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * NOUVEAU (historique des conversations).
 *
 * Une session = une conversation, comme dans ChatGPT. `userId` reste
 * optionnel pour l'instant (le projet n'a pas encore d'authentification
 * admin/utilisateur branchée) — s'il est null, toutes les sessions sont
 * traitées comme appartenant à un seul utilisateur "anonyme". Le jour où
 * une vraie authentification est ajoutée, il suffira de renseigner
 * `userId` avec l'identifiant réel : le reste (repository, service,
 * endpoints) fonctionne déjà par utilisateur.
 */
@Entity
@Table(name = "chat_sessions")
public class ChatSession {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private String userId;

    /** Généré à partir du premier message de la session (cf. ChatHistoryService). */
    private String title;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected ChatSession() {
        // requis par JPA
    }

    public ChatSession(String userId) {
        this.userId = userId;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public UUID getId() { return id; }
    public String getUserId() { return userId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void touch() { this.updatedAt = Instant.now(); }
}
