package com.siryos.behave.chatbot.guides;

import com.siryos.behave.chatbot.guides.dto.GuideSummary;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * NOUVEAU (espace admin) : gestion des guides (PDF, Word...) utilisés par
 * le RAG. Toute la logique "on ne refait pas l'embedding si rien n'a
 * changé" vit dans GuideAdminService — ce contrôleur ne fait qu'exposer
 * les 4 actions attendues par l'écran admin.
 *
 * SÉCURITÉ : ces routes sont protégées par AdminAuthInterceptor (cf.
 * package security) — un token "Authorization: Bearer <token>" valide,
 * obtenu via POST /api/admin/auth/login, est requis pour tout appel ici.
 */
@RestController
@RequestMapping("/api/admin/guides")
@CrossOrigin(origins = "http://localhost:4200")
public class GuideAdminController {

    private final GuideAdminService guideAdminService;

    public GuideAdminController(GuideAdminService guideAdminService) {
        this.guideAdminService = guideAdminService;
    }

    /**
     * Upload d'un guide. Si un guide de même nom existe déjà :
     *  - contenu identique (même hash) -> aucun retraitement (règle obligatoire).
     *  - contenu différent             -> anciens chunks supprimés, ré-embedding + ré-chunking.
     */
    @PostMapping(consumes = "multipart/form-data")
    public ResponseEntity<GuideSummary> upload(@RequestParam("file") MultipartFile file) throws IOException {
        return ResponseEntity.ok(guideAdminService.handleUpload(file));
    }

    @GetMapping
    public List<GuideSummary> list() {
        return guideAdminService.listGuides();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        try {
            guideAdminService.deleteGuide(id);
            return ResponseEntity.noContent().build();
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        } catch (IOException e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    /** Force un ré-embedding manuel (debug / changement de config de chunking), même si le hash est inchangé. */
    @PostMapping("/{id}/reprocess")
    public ResponseEntity<GuideSummary> reprocess(@PathVariable UUID id) {
        try {
            return ResponseEntity.ok(guideAdminService.forceReprocess(id));
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        } catch (IOException e) {
            return ResponseEntity.internalServerError().build();
        }
    }
}
