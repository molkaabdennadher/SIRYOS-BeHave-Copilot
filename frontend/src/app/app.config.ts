import { ApplicationConfig } from '@angular/core';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { routes } from './app.routes';
import { authInterceptor } from './services/auth.interceptor';

export const appConfig: ApplicationConfig = {
  providers: [
    // NOUVEAU (sécurité espace admin) : authInterceptor ajoute le token
    // admin sur les appels /api/admin/** et gère les 401 (déconnexion +
    // redirection vers /admin/login).
    provideHttpClient(withInterceptors([authInterceptor])),
    provideRouter(routes)
  ]
};
