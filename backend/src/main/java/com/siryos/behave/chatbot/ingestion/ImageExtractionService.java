package com.siryos.behave.chatbot.ingestion;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * NOUVEAU (fonctionnalité "affiche la photo du guide").
 *
 * Extrait les images intégrées ("embedded") de chaque page d'un PDF —
 * qu'il s'agisse d'un PDF natif ou d'un PDF converti depuis Word par
 * WordToPdfConverter (on travaille toujours sur du PDF, ce qui permet un
 * traitement unique et une numérotation de page fiable pour les deux cas).
 *
 * Les images sont écrites sur disque sous :
 *   {images-folder}/{sanitizedDocumentId}/page{N}_img{I}.png
 * et servies ensuite par DocumentController#getImage.
 *
 * Les très petites images (icônes, puces, logos décoratifs, filets de
 * séparation) sont ignorées via MIN_DIMENSION, pour ne pas polluer la
 * réponse du chatbot avec des vignettes inutiles.
 */
@Service
public class ImageExtractionService {

    private static final Logger log = LoggerFactory.getLogger(ImageExtractionService.class);
    private static final int MIN_DIMENSION_PX = 80;

    @Value("${behave.docs.images-folder:/tmp/behave-images}")
    private String imagesFolder;

    /**
     * Extrait les images d'un fichier PDF, page par page.
     *
     * @param pdfFile           le PDF (natif ou converti depuis Word)
     * @param sanitizedDocumentId identifiant de dossier déjà assaini
     *                           (cf. DocumentIngestionService#sanitize),
     *                           utilisé pour isoler les images par document.
     * @return une map "numéro de page (1-based)" -> liste de noms de
     *         fichiers image (relatifs au dossier du document), vide si
     *         extraction impossible.
     */
    public Map<Integer, List<String>> extractImages(File pdfFile, String sanitizedDocumentId) {
        Map<Integer, List<String>> result = new LinkedHashMap<>();
        if (pdfFile == null || !pdfFile.exists()) {
            return result;
        }

        File targetDir = new File(imagesFolder, sanitizedDocumentId);

        try {
            Files.createDirectories(targetDir.toPath());

            try (PDDocument document = Loader.loadPDF(pdfFile)) {
                int pageIndex = 0;
                for (PDPage page : document.getPages()) {
                    pageIndex++;
                    List<String> pageImages = extractImagesFromPage(page, targetDir, pageIndex);
                    if (!pageImages.isEmpty()) {
                        result.put(pageIndex, pageImages);
                    }
                }
            }
        } catch (Exception e) {
            // Une extraction ratée pour un fichier ne doit jamais bloquer
            // l'ingestion : les guides sont indexés en texte quoi qu'il
            // arrive, l'illustration est un "plus".
            log.warn("Extraction d'images échouée pour {} : {}", pdfFile.getName(), e.getMessage());
        }

        return result;
    }

    private List<String> extractImagesFromPage(PDPage page, File targetDir, int pageIndex) throws IOException {
        List<String> filenames = new ArrayList<>();
        PDResources resources = page.getResources();
        if (resources == null) {
            return filenames;
        }

        int imgIndex = 0;
        for (COSName xObjectName : resources.getXObjectNames()) {
            PDXObject xObject;
            try {
                xObject = resources.getXObject(xObjectName);
            } catch (Exception e) {
                continue; // objet graphique corrompu/non lisible : on l'ignore
            }

            if (!(xObject instanceof PDImageXObject imageXObject)) {
                continue;
            }

            BufferedImage image;
            try {
                image = imageXObject.getImage();
            } catch (Exception e) {
                continue;
            }

            if (image.getWidth() < MIN_DIMENSION_PX || image.getHeight() < MIN_DIMENSION_PX) {
                continue; // icône / puce / filet décoratif : pas pertinent
            }

            imgIndex++;
            String filename = "page%d_img%d.png".formatted(pageIndex, imgIndex);
            File out = new File(targetDir, filename);
            try {
                ImageIO.write(image, "png", out);
                filenames.add(filename);
            } catch (IOException e) {
                log.warn("Impossible d'écrire l'image {} : {}", filename, e.getMessage());
            }
        }

        return filenames;
    }

    public File getImagesFolder() {
        return new File(imagesFolder);
    }
}
