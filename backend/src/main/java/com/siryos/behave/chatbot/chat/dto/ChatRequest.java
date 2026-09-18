package com.siryos.behave.chatbot.chat.dto;

import java.util.List;

/**
 * MODIFIÉ : ajout de `sessionId` (existant) et de `history` (NOUVEAU).
 *
 * Le frontend Angular doit générer un identifiant de conversation
 * (ex: UUID stocké en localStorage/sessionStorage) et le renvoyer à
 * chaque message, afin que le backend puisse conserver la langue
 * détectée tout au long de la conversation (cf. ConversationLanguageStore).
 * Peut être null : dans ce cas la langue est simplement redétectée
 * message par message, sans persistance.
 *
 * NOUVEAU : `history` contient les derniers tours de la conversation
 * (texte utilisateur + réponse assistant), envoyés par le frontend pour
 * permettre au LLM de comprendre les questions de suivi ("et le bouton
 * à gauche sert à quoi ?" après une question sur une image). Volontairement
 * limité à quelques tours et au texte seul (jamais l'image binaire) pour
 * ne pas alourdir chaque requête — cf. ChatService.MAX_HISTORY_TURNS.
 * Peut être null ou vide : dans ce cas la question est traitée sans
 * contexte conversationnel, comme avant.
 */
public record ChatRequest(String question, ExplanationLevel level, String sessionId, List<HistoryTurn> history) {
    public enum ExplanationLevel { SIMPLE, DETAILLE, TECHNIQUE }

    /**
     * Un tour de conversation précédent. `role` vaut "user" ou "assistant".
     * `text` est le texte affiché à l'écran (pour un message utilisateur
     * avec image jointe, c'est la question posée sur l'image, pas l'image
     * elle-même).
     */
    public record HistoryTurn(String role, String text) {}
}