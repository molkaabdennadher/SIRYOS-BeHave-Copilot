package com.siryos.behave.chatbot.chat.dto;

/**
 * MODIFIÉ :
 *  - `documentId` : identifiant stable du document (nom de fichier assaini),
 *     utilisé par le frontend pour construire l'URL du viewer.
 *  - `pageLabel` : libellé prêt à afficher ("Page 12" ou, pour un Word non
 *     converti en PDF, "Section 3") — évite d'inventer un numéro de page
 *     inexistant.
 *  - `sourceUrl` : URL backend directement cliquable, pointant vers le PDF
 *     (natif ou converti) à la bonne page via le fragment `#page=`.
 *  - `viewablePage` : true si `sourceUrl` pointe vraiment vers un PDF avec
 *     un numéro de page fiable (false pour un Word non converti : le lien
 *     ouvre alors le fichier original, sans navigation de page).
 *  - `section` (NOUVEAU) : titre de section détecté au-dessus du passage
 *     dans le guide (ex: "BeeHive Interface"), null si aucun titre n'a pu
 *     être détecté. Sert à l'Explainable AI ("Quelle documentation ? Guide,
 *     Page, Section").
 *  - `score` (NOUVEAU) : score de similarité vectorielle réel (0 à 1,
 *     cosine) entre la question et ce passage, tel que renvoyé par
 *     PGVector. Jamais un pourcentage de confiance inventé par le LLM —
 *     c'est une métrique technique de retrieval (cf. demande §17).
 */
public record SourceReference(
        String sentenceId,      // conservé pour compat frontend existant = chunkId
        String filename,
        int page,
        String excerpt,
        String documentId,
        String pageLabel,
        String sourceUrl,
        boolean viewablePage,
        String section,
        double score
) {}