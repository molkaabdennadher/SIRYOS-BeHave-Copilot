package com.siryos.behave.chatbot.ingestion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * CORRIGÉ : les conversions échouaient silencieusement pour certains
 * fichiers Word (viewablePage=false, "Section N" au lieu d'une vraie page).
 *
 * Cause typique de LibreOffice headless : quand plusieurs conversions
 * s'enchaînent (un fichier après l'autre pendant l'ingestion), un profil
 * utilisateur LibreOffice partagé peut rester "verrouillé" par un
 * précédent lancement (crash, timeout...), et les conversions suivantes
 * échouent sans message clair.
 *
 * Fix : on force un profil LibreOffice DÉDIÉ et isolé
 * (-env:UserInstallation=...) à chaque conversion, ce qui évite les
 * conflits de verrou. On loggue aussi la sortie complète du process en
 * cas d'échec, pour diagnostiquer précisément (au lieu d'un simple
 * "conversion échouée").
 */
@Component
public class WordToPdfConverter {

    private static final Logger log = LoggerFactory.getLogger(WordToPdfConverter.class);

    @Value("${behave.docs.converted-folder:/tmp/behave-converted-pdfs}")
    private String convertedFolder;

    @Value("${behave.docs.libreoffice-binary:soffice}")
    private String libreOfficeBinary;

    public File convertIfNeeded(Resource wordResource) {
        try {
            Files.createDirectories(Path.of(convertedFolder));
            String baseName = stripExtension(wordResource.getFilename());
            File targetPdf = new File(convertedFolder, baseName + ".pdf");

            if (targetPdf.exists() && targetPdf.length() > 0) {
                return targetPdf;
            }

            File sourceFile = wordResource.getFile();

            // Profil isolé par fichier -> évite les conflits de verrou
            // LibreOffice quand plusieurs conversions s'enchaînent.
            File profileDir = new File(convertedFolder, "lo_profile_" + Math.abs(baseName.hashCode()));
            Files.createDirectories(profileDir.toPath());
            String profileUrl = profileDir.toURI().toString();

            ProcessBuilder pb = new ProcessBuilder(
                    libreOfficeBinary,
                    "--headless", "--norestore",
                    "-env:UserInstallation=" + profileUrl,
                    "--convert-to", "pdf",
                    "--outdir", convertedFolder,
                    sourceFile.getAbsolutePath()
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();

            String output;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                output = reader.lines().collect(Collectors.joining("\n"));
            }

            boolean finished = process.waitFor(120, TimeUnit.SECONDS);

            if (finished && process.exitValue() == 0 && targetPdf.exists()) {
                return targetPdf;
            }

            log.warn("Conversion Word->PDF échouée pour {} (exitCode={}, finished={}). Sortie LibreOffice :\n{}",
                    wordResource.getFilename(), finished ? process.exitValue() : "timeout", finished, output);
            return null;
        } catch (Exception e) {
            log.warn("Conversion Word->PDF impossible pour {} : {}", wordResource.getFilename(), e.getMessage(), e);
            return null;
        }
    }

    public File getConvertedFolder() {
        return new File(convertedFolder);
    }

    private String stripExtension(String filename) {
        if (filename == null) return "document";
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }
}