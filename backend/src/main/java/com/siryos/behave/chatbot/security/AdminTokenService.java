package com.siryos.behave.chatbot.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NOUVEAU (sécurité espace admin).
 *
 * Service de session admin volontairement simple (pas de Spring Security,
 * pas de dépendance supplémentaire) : après une connexion réussie
 * (cf. AdminAuthController), un token opaque aléatoire est généré et gardé
 * en mémoire avec sa date d'expiration. Chaque requête vers /api/admin/**
 * doit ensuite présenter ce token via l'en-tête
 * "Authorization: Bearer <token>" (cf. AdminAuthInterceptor).
 *
 * Limite connue : les tokens vivent en mémoire du process — un redémarrage
 * du backend déconnecte tous les admins (comportement acceptable ici,
 * l'espace admin n'est pas critique en disponibilité). Si l'application
 * est un jour déployée avec plusieurs instances derrière un load-balancer,
 * il faudra externaliser ce store (Redis, base, ou passer à un vrai JWT
 * signé sans état).
 */
@Service
public class AdminTokenService {

    private final Map<String, Instant> activeTokens = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    private final int tokenTtlMinutes;

    public AdminTokenService(@Value("${behave.admin.token-ttl-minutes:480}") int tokenTtlMinutes) {
        this.tokenTtlMinutes = tokenTtlMinutes;
    }

    public String issueToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        activeTokens.put(token, Instant.now().plusSeconds(tokenTtlMinutes * 60L));
        return token;
    }

    public int getTokenTtlMinutes() {
        return tokenTtlMinutes;
    }

    public boolean isValid(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        Instant expiry = activeTokens.get(token);
        if (expiry == null) {
            return false;
        }
        if (Instant.now().isAfter(expiry)) {
            activeTokens.remove(token);
            return false;
        }
        return true;
    }

    public void revoke(String token) {
        if (token != null) {
            activeTokens.remove(token);
        }
    }
}
