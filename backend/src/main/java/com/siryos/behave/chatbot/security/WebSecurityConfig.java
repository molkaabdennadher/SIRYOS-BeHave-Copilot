package com.siryos.behave.chatbot.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * NOUVEAU (sécurité espace admin).
 *
 * Enregistre AdminAuthInterceptor sur :
 *  - /api/admin/**       : toutes les routes de GuideAdminController
 *                          (liste/upload/suppression/réindexation des guides).
 *  - /api/documents/ingest : ancien endpoint d'indexation manuelle, tout
 *                          aussi sensible (déclenche un ré-embedding complet).
 *
 * Exclusion volontaire de /api/admin/auth/** (login/logout), sinon
 * personne ne pourrait jamais obtenir de token pour se connecter.
 */
@Configuration
public class WebSecurityConfig implements WebMvcConfigurer {

    private final AdminTokenService tokenService;

    public WebSecurityConfig(AdminTokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AdminAuthInterceptor(tokenService))
                .addPathPatterns("/api/admin/**", "/api/documents/ingest")
                .excludePathPatterns("/api/admin/auth/**");
    }
}
