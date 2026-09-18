import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from './auth.service';

/**
 * NOUVEAU (sécurité espace admin).
 *
 * Bloque l'accès à /admin tant qu'aucun token valide n'est en session et
 * redirige vers /admin/login. Le token lui-même est revérifié à chaque
 * appel API par le backend (cf. AdminAuthInterceptor côté Java) : ce guard
 * n'est qu'une protection UX côté front (éviter d'afficher l'écran avant
 * même le premier appel réseau), pas la seule ligne de défense.
 */
export const adminGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  const router = inject(Router);

  if (auth.isAuthenticated()) {
    return true;
  }
  return router.parseUrl('/admin/login');
};
