package com.siryos.behave.chatbot.chat.dto;

/**
 * NOUVEAU : référence à une image extraite d'un guide BeHave (PDF natif ou
 * Word converti) et jugée pertinente pour répondre à la question posée.
 *
 *  - `documentId` : nom de fichier original du guide.
 *  - `fileName`   : nom du fichier image extrait.
 *  - `page`       : page du guide sur laquelle l'image apparaît.
 *  - `url`        : URL backend directement affichable (<img src="...">),
 *                    servie par DocumentController#getImage.
 *  - `section` (NOUVEAU) : titre de section détecté sur la page de l'image,
 *     null si non détecté.
 *  - `explanation` (NOUVEAU) : courte phrase expliquant pourquoi cette
 *     image a été sélectionnée (Explainable AI multimodal, cf. demande §16).
 */
public record ImageReference(
        String documentId,
        String fileName,
        int page,
        String url,
        String section,
        String explanation
) {}