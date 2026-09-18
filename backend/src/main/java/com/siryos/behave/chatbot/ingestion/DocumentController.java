package com.siryos.behave.chatbot.ingestion;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;

@RestController
@RequestMapping("/api/documents")
@CrossOrigin(origins = "http://localhost:4200")
public class DocumentController {

    private final DocumentIngestionService ingestionService;
    private final WordToPdfConverter wordToPdfConverter;
    private final ImageExtractionService imageExtractionService;

    @Value("${behave.docs.folder}")
    private String docsFolder;

    // NOUVEAU (espace admin) : les guides uploadés par l'admin vivent
    // maintenant sur disque (storage-dir), pas seulement sur le classpath.
    @Value("${behave.docs.storage-dir}")
    private String storageDir;

    public DocumentController(DocumentIngestionService ingestionService,
                               WordToPdfConverter wordToPdfConverter,
                               ImageExtractionService imageExtractionService) {
        this.ingestionService = ingestionService;
        this.wordToPdfConverter = wordToPdfConverter;
        this.imageExtractionService = imageExtractionService;
    }

    /**
     * Déclenche l'indexation des guides BeHave.
     * A appeler une fois au déploiement, ou après mise à jour des guides.
     */
    @PostMapping("/ingest")
    public ResponseEntity<DocumentIngestionService.IngestionReport> ingest() throws Exception {
        return ResponseEntity.ok(ingestionService.ingestAllDocuments());
    }

    /**
     * MODIFIÉ (point 4/5 de la demande) : sert le fichier associé à une
     * source citée par le chatbot, en l'ouvrant directement à la bonne page
     * grâce au fragment `#page=` géré nativement par les visionneuses PDF
     * des navigateurs (et par ngx-extended-pdf-viewer côté Angular).
     *
     * Pour un fichier Word, on sert automatiquement la version PDF
     * convertie à l'ingestion si elle existe (pagination fiable) ; sinon on
     * sert le fichier Word original (le frontend ne pourra alors pas
     * naviguer à une page précise — cf. SourceReference.viewablePage).
     */
    @GetMapping("/file/{filename:.+}")
    public ResponseEntity<Resource> getFile(@PathVariable String filename) throws Exception {
        String lower = filename.toLowerCase();

        if (lower.endsWith(".doc") || lower.endsWith(".docx")) {
            String baseName = filename.substring(0, filename.lastIndexOf('.'));
            File convertedPdf = new File(wordToPdfConverter.getConvertedFolder(), baseName + ".pdf");
            if (convertedPdf.exists()) {
                return ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_PDF)
                        .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + baseName + ".pdf\"")
                        .body(new FileSystemResource(convertedPdf));
            }
        }

        // MODIFIÉ (espace admin) : on cherche d'abord dans storage-dir
        // (guides gérés par l'admin, cf. GuideAdminService), et on ne
        // retombe sur le classpath legacy que si le fichier n'y est pas
        // (compatibilité descendante, avant la première migration).
        File diskFile = new File(storageDir, filename);
        Resource resource = diskFile.exists()
                ? new FileSystemResource(diskFile)
                : new PathMatchingResourcePatternResolver().getResource(docsFolder + filename);

        if (!resource.exists()) {
            return ResponseEntity.notFound().build();
        }

        MediaType contentType = lower.endsWith(".pdf")
                ? MediaType.APPLICATION_PDF
                : MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");

        return ResponseEntity.ok()
                .contentType(contentType)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"")
                .body(resource);
    }

    /**
     * NOUVEAU (fonctionnalité "affiche la photo du guide") : sert une
     * image extraite d'un guide à l'ingestion (cf. ImageExtractionService).
     * `docFolder` correspond à `ImageReference.documentId` assaini —
     * la valeur exacte à utiliser est fournie directement dans
     * `ChatResponse.images[].url` par le backend, le frontend n'a donc
     * jamais besoin de construire cette URL lui-même.
     */
    @GetMapping("/image/{docFolder}/{filename:.+}")
    public ResponseEntity<Resource> getImage(@PathVariable String docFolder, @PathVariable String filename) {
        File imagesRoot = imageExtractionService.getImagesFolder();
        File imageFile = new File(new File(imagesRoot, docFolder), filename);

        // Garde-fou anti path-traversal : docFolder/filename viennent de
        // l'URL, on vérifie que le chemin résolu reste bien sous le dossier
        // d'images (docFolder est normalement déjà assaini alnum-only à
        // l'ingestion, mais on ne fait jamais confiance à l'entrée réseau).
        try {
            String rootCanonical = imagesRoot.getCanonicalPath();
            String fileCanonical = imageFile.getCanonicalPath();
            if (!fileCanonical.startsWith(rootCanonical + File.separator)) {
                return ResponseEntity.notFound().build();
            }
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }

        if (!imageFile.exists() || !imageFile.isFile()) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .header(HttpHeaders.CACHE_CONTROL, "max-age=86400") // images statiques, cache navigateur 24h
                .body(new FileSystemResource(imageFile));
    }
}
