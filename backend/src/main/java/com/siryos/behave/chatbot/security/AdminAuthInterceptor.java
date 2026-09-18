package com.siryos.behave.chatbot.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * NOUVEAU (sécurité espace admin).
 *
 * Vérifie l'en-tête "Authorization: Bearer <token>" sur toutes les routes
 * protégées (cf. WebSecurityConfig pour la liste exacte / les exclusions).
 * Une requête sans token valide reçoit une 401, ce que le front (guide
 * admin service + intercepteur HTTP Angular) interprète comme "reconnecte-
 * toi" et redirige vers /admin/login.
 */
public class AdminAuthInterceptor implements HandlerInterceptor {

    private final AdminTokenService tokenService;

    public AdminAuthInterceptor(AdminTokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // Les pré-vérifications CORS envoient un OPTIONS sans en-tête
        // Authorization : on les laisse toujours passer, sinon le
        // navigateur bloque la vraie requête avant même qu'elle parte.
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String header = request.getHeader("Authorization");
        String token = (header != null && header.startsWith("Bearer ")) ? header.substring(7) : null;

        if (tokenService.isValid(token)) {
            return true;
        }

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        try {
            response.getWriter().write("{\"message\":\"Authentification admin requise.\"}");
        } catch (Exception ignored) {
            // rien de plus à faire si le flux de réponse est déjà fermé
        }
        return false;
    }
}
