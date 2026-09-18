package com.siryos.behave.chatbot.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * NOUVEAU (sécurité espace admin).
 *
 * Point d'entrée unique, NON protégé, pour se connecter à l'espace admin
 * (cf. AdminAuthInterceptor qui exclut explicitement ce chemin). Les
 * identifiants attendus sont définis en configuration
 * (behave.admin.username / behave.admin.password, cf. application.yml,
 * surchargeables par variables d'environnement).
 */
@RestController
@RequestMapping("/api/admin/auth")
@CrossOrigin(origins = "http://localhost:4200")
public class AdminAuthController {

    private final AdminTokenService tokenService;

    @Value("${behave.admin.username}")
    private String expectedUsername;

    @Value("${behave.admin.password}")
    private String expectedPassword;

    public AdminAuthController(AdminTokenService tokenService) {
        this.tokenService = tokenService;
    }

    public record LoginRequest(String username, String password) {}
    public record LoginResponse(String token, long expiresInMinutes) {}

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request) {
        String username = request.username() == null ? "" : request.username().trim();
        String password = request.password() == null ? "" : request.password();

        boolean valid = expectedUsername.equals(username) && expectedPassword.equals(password);
        if (!valid) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new ErrorBody("Identifiant ou mot de passe incorrect."));
        }

        String token = tokenService.issueToken();
        return ResponseEntity.ok(new LoginResponse(token, tokenService.getTokenTtlMinutes()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            tokenService.revoke(authHeader.substring("Bearer ".length()));
        }
        return ResponseEntity.noContent().build();
    }

    public record ErrorBody(String message) {}
}
