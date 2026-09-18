package com.siryos.behave.chatbot.chat.dto;

import java.util.List;

/**
 * MODIFIÉ : ajout de `language` (FR/EN/AR) — utile au frontend pour, par
 * exemple, aligner le texte à droite en arabe (RTL) ou afficher le bon
 * libellé "Sources / Sources / المصادر".
 *
 * NOUVEAU : ajout de `images` — les images extraites des guides BeHave
 * (captures d'écran, schémas...) trouvées sur les mêmes pages que les
 * sources utilisées pour répondre. Le frontend peut les afficher sous
 * forme de vignettes (<img src="{ImageReference.url}">) à côté de la
 * réponse. Liste vide si aucune image pertinente, ou si le retrieval
 * était de faible confiance.
 *
 * NOUVEAU (historique des conversations) : `sessionId` est désormais
 * toujours renseigné dans la réponse (même si le frontend n'en avait
 * fourni aucun dans la requête) — c'est ChatController qui résout/crée la
 * session côté serveur avant d'appeler ChatService. Le frontend doit
 * mémoriser cette valeur (ex: dans son état de conversation courant) et la
 * renvoyer dans les requêtes suivantes pour rester dans la même session.
 */
public record ChatResponse(String answer, List<SourceReference> sources, String language, List<ImageReference> images, String sessionId) {}
