package com.siryos.behave.chatbot.guides;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.siryos.behave.chatbot.guides.dto.GuideSummary;
import com.siryos.behave.chatbot.ingestion.DocumentIngestionService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;

/**
 * NOUVEAU (espace admin) — le cœur de la demande :
 *
 *   "si les guides sont déjà les mêmes, on ne fait pas l'embedding de
 *    nouveau, mais si l'admin a ajouté un nouveau guide ou a changé un
 *    guide ou supprimé, il faut faire l'embedding et chunking de nouveau"
 *
 * Le hash SHA-256 du contenu du fichier est la SEULE source de vérité
 * pour décider s'il faut retraiter — jamais la date de modification ni le
 * nom de fichier seuls, qui peuvent être trompeurs (ré-upload du même
 * fichier, horloge du poste client, etc.).
 */
@Service
public class GuideAdminService {

    private static final Logger log = LoggerFactory.getLogger(GuideAdminService.class);

    private final GuideRepository guideRepository;
    private final DocumentIngestionService ingestionService;
    private final VectorStore vectorStore;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${behave.docs.storage-dir}")
    private String storageDir;

    @Value("${behave.docs.folder}")
    private String legacyClasspathFolder;

    public GuideAdminService(GuideRepository guideRepository,
                              DocumentIngestionService ingestionService,
                              VectorStore vectorStore) {
        this.guideRepository = guideRepository;
        this.ingestionService = ingestionService;
        this.vectorStore = vectorStore;
    }

    // ------------------------------------------------------------------
    // Upload — logique de détection de changement (obligatoire)
    // ------------------------------------------------------------------

    @Transactional
    public GuideSummary handleUpload(MultipartFile file) throws IOException {
        String filename = sanitizeFilename(file.getOriginalFilename());
        byte[] bytes = file.getBytes();
        String hash = sha256(bytes);

        Optional<Guide> existingOpt = guideRepository.findByFilename(filename);

        if (existingOpt.isPresent()) {
            Guide existing = existingOpt.get();
            if (hash.equals(existing.getContentHash())) {
                // AUCUN changement -> on ne touche NI au fichier NI au
                // vector store. C'est le cas exigé par la demande.
                log.info("Guide '{}' inchangé (hash identique) — embedding/chunking ignoré.", filename);
                return GuideSummary.from(existing);
            }
            log.info("Guide '{}' modifié — ré-embedding + ré-chunking.", filename);
            return reprocessGuide(existing, bytes, hash);
        }

        log.info("Nouveau guide '{}' — embedding + chunking.", filename);
        Guide created = new Guide(filename, resolveStoragePath(filename), hash);
        created = guideRepository.save(created);
        return reprocessGuide(created, bytes, hash);
    }

    /** Force un retraitement, même si le hash n'a pas changé (bouton "Réindexer" en admin, debug). */
    @Transactional
    public GuideSummary forceReprocess(UUID guideId) throws IOException {
        Guide guide = guideRepository.findById(guideId)
                .orElseThrow(() -> new NoSuchElementException("Guide introuvable : " + guideId));
        byte[] bytes = Files.readAllBytes(Path.of(guide.getStoragePath()));
        return reprocessGuide(guide, bytes, guide.getContentHash());
    }

    private GuideSummary reprocessGuide(Guide guide, byte[] bytes, String hash) throws IOException {
        // 1) Purge des anciens chunks de CE guide uniquement (pas des autres).
        deleteChunksOf(guide);

        // 2) Écriture/remplacement du fichier sur disque.
        Path target = Path.of(resolveStoragePath(guide.getFilename()));
        Files.createDirectories(target.getParent());
        Files.write(target, bytes);
        guide.setStoragePath(target.toString());
        guide.setContentHash(hash);

        // 3) Chunking + embedding + indexation PGVector.
        try {
            List<Document> chunks = ingestionService.ingestFile(target.toFile(), guide.getFilename());
            if (!chunks.isEmpty()) {
                vectorStore.add(chunks);
            }
            guide.setChunkIdsJson(objectMapper.writeValueAsString(chunks.stream().map(Document::getId).toList()));
            guide.setChunkCount(chunks.size());
            guide.setStatus(GuideStatus.PROCESSED);
            guide.setLastError(null);
        } catch (Exception e) {
            log.error("Échec du traitement du guide '{}'", guide.getFilename(), e);
            guide.setStatus(GuideStatus.FAILED);
            guide.setLastError(e.getMessage());
        }
        guide.setLastProcessedAt(Instant.now());
        return GuideSummary.from(guideRepository.save(guide));
    }

    // ------------------------------------------------------------------
    // Liste / suppression
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<GuideSummary> listGuides() {
        return guideRepository.findAllByOrderByFilenameAsc().stream().map(GuideSummary::from).toList();
    }

    @Transactional
    public void deleteGuide(UUID guideId) throws IOException {
        Guide guide = guideRepository.findById(guideId)
                .orElseThrow(() -> new NoSuchElementException("Guide introuvable : " + guideId));

        deleteChunksOf(guide);

        Path path = Path.of(guide.getStoragePath());
        Files.deleteIfExists(path);

        guideRepository.delete(guide);
        log.info("Guide '{}' supprimé (fichier + {} chunks PGVector).", guide.getFilename(), guide.getChunkCount());
    }

    @SuppressWarnings("unchecked")
    private void deleteChunksOf(Guide guide) {
        if (guide.getChunkIdsJson() == null || guide.getChunkIdsJson().isBlank()) {
            return;
        }
        try {
            List<String> ids = objectMapper.readValue(guide.getChunkIdsJson(), List.class);
            if (!ids.isEmpty()) {
                vectorStore.delete(ids);
            }
        } catch (Exception e) {
            log.warn("Impossible de parser/supprimer les anciens chunks du guide '{}' : {}",
                    guide.getFilename(), e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Migration ponctuelle des guides historiques (classpath -> admin)
    // ------------------------------------------------------------------

    /**
     * Au premier démarrage après cette mise à jour (table `guides` vide),
     * importe automatiquement les guides déjà présents dans
     * src/main/resources/docs pour qu'ils apparaissent dans l'écran admin
     * comme n'importe quel autre guide, avec leur hash calculé. Les ids de
     * chunk générés étant déterministes (dérivés du nom de fichier + page
     * + index), une éventuelle indexation PGVector déjà faite via l'ancien
     * endpoint POST /api/documents/ingest est mise à jour en place
     * (upsert), jamais dupliquée.
     */
    @PostConstruct
    public void migrateLegacyClasspathDocsOnce() {
        if (guideRepository.count() > 0) {
            return;
        }
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            var resources = resolver.getResources(legacyClasspathFolder + "*.{pdf,docx,doc}");
            if (resources.length == 0) {
                return;
            }
            log.info("Aucun guide en base : import ponctuel de {} guide(s) historique(s) depuis {}.",
                    resources.length, legacyClasspathFolder);
            for (var resource : resources) {
                String filename = sanitizeFilename(resource.getFilename());
                byte[] bytes = resource.getInputStream().readAllBytes();
                String hash = sha256(bytes);
                Guide guide = new Guide(filename, resolveStoragePath(filename), hash);
                guide = guideRepository.save(guide);
                try {
                    reprocessGuide(guide, bytes, hash);
                } catch (Exception e) {
                    log.warn("Import du guide historique '{}' échoué : {}", filename, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("Migration des guides historiques impossible : {}", e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Utilitaires
    // ------------------------------------------------------------------

    private String resolveStoragePath(String filename) {
        return new File(storageDir, filename).getPath();
    }

    private String sanitizeFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            throw new IllegalArgumentException("Nom de fichier manquant.");
        }
        // Anti path-traversal : on ne garde que le nom de fichier, jamais un chemin.
        return Path.of(filename).getFileName().toString();
    }

    private String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponible", e);
        }
    }
}
