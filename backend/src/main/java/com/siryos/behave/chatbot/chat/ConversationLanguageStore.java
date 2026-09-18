package com.siryos.behave.chatbot.chat;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Mémorise la langue détectée pour chaque conversation (sessionId envoyé
 * par le frontend Angular), afin de respecter la consigne :
 * "conserve cette langue pendant toute la conversation, sauf si
 * l'utilisateur change volontairement de langue".
 *
 * Implémentation volontairement simple (mémoire process, pas de TTL) :
 * suffisant pour une V0/V1. A remplacer par un cache (Caffeine/Redis) avec
 * expiration si le trafic augmente ou si l'app tourne en plusieurs
 * instances.
 */
@Component
public class ConversationLanguageStore {

    private final ConcurrentMap<String, LanguageDetector.Language> sessions = new ConcurrentHashMap<>();

    public void set(String sessionId, LanguageDetector.Language language) {
        if (sessionId != null && !sessionId.isBlank()) {
            sessions.put(sessionId, language);
        }
    }

    public LanguageDetector.Language getOrDefault(String sessionId, LanguageDetector.Language fallback) {
        return sessions.getOrDefault(sessionId, fallback);
    }
}
