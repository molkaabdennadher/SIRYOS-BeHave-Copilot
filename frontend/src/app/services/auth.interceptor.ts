import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, throwError } from 'rxjs';
import { API_ORIGIN } from '../shared/api';
import { AuthService } from './auth.service';

/**
 * NOUVEAU (sécurité espace admin).
 *
 * - Ajoute automatiquement "Authorization: Bearer <token>" sur les appels
 *   vers /api/admin/** (sauf /api/admin/auth/** : pas de token à
 *   présenter pour se connecter).
 * - Si le backend répond 401 sur une route admin (token absent, expiré ou
 *   invalidé après un redémarrage backend), nettoie la session locale et
 *   renvoie vers /admin/login — évite que l'écran reste bloqué sur une
 *   erreur réseau générique comme avant.
 */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const authService = inject(AuthService);
  const router = inject(Router);

  const isAdminApiCall = req.url.startsWith(`${API_ORIGIN}/api/admin`);
  const isAuthCall = req.url.startsWith(`${API_ORIGIN}/api/admin/auth`);

  let outgoing = req;
  if (isAdminApiCall && !isAuthCall) {
    const token = authService.getToken();
    if (token) {
      outgoing = req.clone({ setHeaders: { Authorization: `Bearer ${token}` } });
    }
  }

  return next(outgoing).pipe(
    catchError((err: HttpErrorResponse) => {
      if (isAdminApiCall && !isAuthCall && err.status === 401) {
        authService.handleUnauthorized();
        router.navigate(['/admin/login']);
      }
      return throwError(() => err);
    })
  );
};
