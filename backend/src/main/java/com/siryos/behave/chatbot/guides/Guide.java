package com.siryos.behave.chatbot.guides;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * NOUVEAU (espace admin).
 *
 * Une ligne = un fichier guide géré par l'admin (PDF, Word...). C'est la
 * source de vérité qui permet de répondre à la règle demandée :
 *   "si le guide n'a pas changé, on ne refait JAMAIS l'embedding/chunking".
 *
 * Le champ clé est `contentHash` : un SHA-256 du contenu binaire du
 * fichier. À chaque upload, GuideAdminService compare ce hash à celui déjà
 * enregistré :
 *   - hash identique      -> rien à faire, on ne touche ni au fichier ni
 *                             au vector store (cf. demande : obligatoire).
 *   - hash différent       -> le guide a changé : on supprime ses anciens
 *                             chunks (via chunkIds) puis on réingère.
 *   - pas de ligne existante -> nouveau guide : on ingère normalement.
 *
 * `chunkIds` conserve la liste des ids (UUID stables) des chunks
 * actuellement indexés dans PGVector pour ce guide, en JSON — ce qui
 * permet une suppression ciblée et fiable (vectorStore.delete(ids)) sans
 * dépendre d'un filtre de métadonnées.
 */
@Entity
@Table(name = "guides")
public class Guide {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private java.util.UUID id;

    @Column(nullable = false, unique = true)
    private String filename;

    /** Chemin absolu du fichier sur disque, sous behave.docs.storage-dir. */
    @Column(nullable = false)
    private String storagePath;

    /** SHA-256 (hex) du contenu binaire du fichier — cœur de la détection de changement. */
    @Column(nullable = false, length = 64)
    private String contentHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private GuideStatus status;

    /**
     * Ids (UUID) des chunks actuellement dans PGVector pour ce guide, sérialisés en JSON.
     *
     * CORRIGÉ (bug "Large Objects may not be used in auto-commit mode") :
     * l'annotation @Lob combinée à un champ String sur PostgreSQL fait
     * basculer Hibernate sur le mapping JDBC CLOB, qui utilise l'API des
     * "Large Objects" de PostgreSQL (pg_largeobject). Cette API EXIGE que
     * chaque accès se fasse à l'intérieur d'une transaction explicite
     * (non auto-commit) : c'est exactement pour ça que
     * GuideAdminController.list() (appel non transactionnel, cf.
     * GuideAdminService.listGuides()) plantait avec
     * PSQLException: "Large Objects may not be used in auto-commit mode",
     * empêchant tout simplement l'écran admin d'afficher la liste des
     * guides (le front recevait une 500 et affichait "Impossible de
     * contacter le backend").
     *
     * columnDefinition = "text" suffit à lui seul pour stocker une chaîne
     * de taille arbitraire en colonne PostgreSQL `text` classique (pas de
     * limite de taille pratique) : @Lob est inutile ici et c'est lui qui
     * causait le problème. En le retirant, Hibernate lit/écrit ce champ
     * comme un simple VARCHAR/text, sans transaction obligatoire.
     */
    @Column(columnDefinition = "text")
    private String chunkIdsJson;

    private int chunkCount;

    @Column(nullable = false)
    private Instant uploadedAt;

    private Instant lastProcessedAt;

    /** Message d'erreur si status = FAILED, pour affichage dans l'écran admin. */
    @Column(columnDefinition = "text")
    private String lastError;

    protected Guide() {
        // requis par JPA
    }

    public Guide(String filename, String storagePath, String contentHash) {
        this.filename = filename;
        this.storagePath = storagePath;
        this.contentHash = contentHash;
        this.status = GuideStatus.PENDING;
        this.uploadedAt = Instant.now();
    }

    public java.util.UUID getId() { return id; }
    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }
    public String getStoragePath() { return storagePath; }
    public void setStoragePath(String storagePath) { this.storagePath = storagePath; }
    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }
    public GuideStatus getStatus() { return status; }
    public void setStatus(GuideStatus status) { this.status = status; }
    public String getChunkIdsJson() { return chunkIdsJson; }
    public void setChunkIdsJson(String chunkIdsJson) { this.chunkIdsJson = chunkIdsJson; }
    public int getChunkCount() { return chunkCount; }
    public void setChunkCount(int chunkCount) { this.chunkCount = chunkCount; }
    public Instant getUploadedAt() { return uploadedAt; }
    public Instant getLastProcessedAt() { return lastProcessedAt; }
    public void setLastProcessedAt(Instant lastProcessedAt) { this.lastProcessedAt = lastProcessedAt; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
}
