import { Injectable, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, tap } from 'rxjs';
import { API_ORIGIN } from '../shared/api';

const STORAGE_KEY = 'behave_admin_token';

interface LoginResponse {
  token: string;
  expiresInMinutes: number;
}

/**
 * NOUVEAU (sécurité espace admin).
 *
 * Gère la connexion/déconnexion de l'espace admin côté front, en miroir
 * de AdminAuthController côté backend. Le token est gardé en
 * sessionStorage (effacé à la fermeture de l'onglet, contrairement à
 * localStorage) — cohérent avec le fait que l'espace admin n'a pas
 * vocation à rester ouvert indéfiniment sur un poste partagé.
 *
 * `isAuthenticated` est un signal pour que adminGuard et
 * AdminDashboardComponent réagissent immédiatement à une connexion/
 * déconnexion sans recharger la page.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly baseUrl = `${API_ORIGIN}/api/admin/auth`;

  readonly isAuthenticated = signal(!!this.readToken());

  constructor(private http: HttpClient) {}

  getToken(): string | null {
    return this.readToken();
  }

  login(username: string, password: string): Observable<LoginResponse> {
    return this.http.post<LoginResponse>(`${this.baseUrl}/login`, { username, password }).pipe(
      tap((res) => {
        sessionStorage.setItem(STORAGE_KEY, res.token);
        this.isAuthenticated.set(true);
      })
    );
  }

  logout(): void {
    const token = this.readToken();
    sessionStorage.removeItem(STORAGE_KEY);
    this.isAuthenticated.set(false);
    if (token) {
      // "fire and forget" : on ne bloque jamais la déconnexion côté UI
      // sur la réponse du backend (le token local est de toute façon
      // supprimé, donc les futurs appels admin seront rejetés).
      this.http.post(`${this.baseUrl}/logout`, null).subscribe({ error: () => {} });
    }
  }

  /** Appelé par l'intercepteur HTTP quand le backend répond 401 sur une route admin. */
  handleUnauthorized(): void {
    sessionStorage.removeItem(STORAGE_KEY);
    this.isAuthenticated.set(false);
  }

  private readToken(): string | null {
    return sessionStorage.getItem(STORAGE_KEY);
  }
}
