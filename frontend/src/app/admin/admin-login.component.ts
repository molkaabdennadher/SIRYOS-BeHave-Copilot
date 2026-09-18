import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { AuthService } from '../services/auth.service';

/**
 * NOUVEAU (sécurité espace admin).
 *
 * Écran de connexion à l'espace admin. Réutilise volontairement les mêmes
 * codes visuels que AdminDashboardComponent (fond dégradé, halos, logo)
 * pour que la transition entre les deux écrans soit cohérente.
 */
@Component({
  selector: 'app-admin-login',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink],
  template: `
    <div class="login-page">
      <div class="bg-glow bg-glow-1"></div>
      <div class="bg-glow bg-glow-2"></div>

      <form class="login-card" (ngSubmit)="submit()">
        <div class="logo-shell">
          <img src="assets/behave-copilot-mark.png" alt="Logo BeHave Copilot" class="brand-logo">
        </div>
        <span class="eyebrow">Espace administrateur</span>
        <h1>Connexion</h1>
        <p class="hint">Réservé aux administrateurs de BeHave Copilot.</p>

        <label class="field">
          <span>Identifiant</span>
          <input
            type="text"
            name="username"
            [(ngModel)]="username"
            autocomplete="username"
            required
            [disabled]="loading">
        </label>

        <label class="field">
          <span>Mot de passe</span>
          <input
            type="password"
            name="password"
            [(ngModel)]="password"
            autocomplete="current-password"
            required
            [disabled]="loading">
        </label>

        <div class="error" *ngIf="error">{{ error }}</div>

        <button type="submit" class="submit-btn" [disabled]="loading || !username || !password">
          {{ loading ? 'Connexion…' : 'Se connecter' }}
        </button>

        <a routerLink="/" class="back-link"><span>←</span> Retour au chat</a>
      </form>
    </div>
  `,
  styles: [`
    :host { display: block; font-family: 'Inter', system-ui, sans-serif; }

    .login-page {
      position: relative;
      min-height: 100vh;
      background: #0c1130;
      overflow: hidden;
      display: flex;
      align-items: center;
      justify-content: center;
      padding: 24px;
    }

    .bg-glow {
      position: absolute;
      border-radius: 50%;
      filter: blur(90px);
      opacity: 0.3;
      pointer-events: none;
      z-index: 0;
    }
    .bg-glow-1 { width: 380px; height: 380px; background: #ef8b3e; top: -120px; left: -80px; }
    .bg-glow-2 { width: 320px; height: 320px; background: #5865f2; bottom: -100px; right: -60px; }

    .login-card {
      position: relative;
      z-index: 1;
      width: 100%;
      max-width: 360px;
      background: rgba(18, 24, 63, 0.65);
      backdrop-filter: blur(14px);
      border: 1px solid rgba(255,255,255,0.08);
      border-radius: 20px;
      padding: 32px 28px 26px;
      display: flex;
      flex-direction: column;
      align-items: center;
      text-align: center;
      gap: 4px;
    }

    .logo-shell {
      width: 52px;
      height: 52px;
      border-radius: 14px;
      overflow: hidden;
      background: rgba(255,255,255,0.06);
      display: flex;
      align-items: center;
      justify-content: center;
      margin-bottom: 10px;
    }
    .brand-logo { width: 100%; height: 100%; object-fit: cover; }

    .eyebrow { color: #ef8b3e; font-size: 11.5px; font-weight: 700; letter-spacing: 0.06em; text-transform: uppercase; }
    h1 { color: #fff; font-size: 21px; font-weight: 700; margin: 4px 0 0; letter-spacing: -0.01em; }
    .hint { color: #9aa4d4; font-size: 12.5px; margin: 4px 0 20px; }

    .field {
      width: 100%;
      display: flex;
      flex-direction: column;
      gap: 6px;
      text-align: left;
      margin-bottom: 14px;
    }
    .field span { color: #c4cbef; font-size: 12px; font-weight: 600; }
    .field input {
      background: rgba(255,255,255,0.06);
      border: 1px solid rgba(255,255,255,0.14);
      border-radius: 10px;
      padding: 11px 13px;
      color: #fff;
      font-size: 14px;
      outline: none;
      transition: border-color 0.2s, background 0.2s;
    }
    .field input:focus { border-color: #ef8b3e; background: rgba(255,255,255,0.09); }
    .field input:disabled { opacity: 0.6; }

    .error {
      width: 100%;
      background: rgba(248,113,113,0.12);
      color: #f87171;
      font-size: 12.5px;
      font-weight: 600;
      padding: 9px 12px;
      border-radius: 10px;
      margin-bottom: 14px;
      text-align: left;
    }

    .submit-btn {
      width: 100%;
      border: none;
      background: linear-gradient(135deg, #ef8b3e, #f2a663);
      color: #1a2456;
      font-weight: 700;
      font-size: 14px;
      padding: 12px;
      border-radius: 10px;
      cursor: pointer;
      transition: opacity 0.2s;
    }
    .submit-btn:disabled { opacity: 0.55; cursor: not-allowed; }
    .submit-btn:hover:not(:disabled) { opacity: 0.92; }

    .back-link {
      display: flex;
      align-items: center;
      gap: 6px;
      color: #9aa4d4;
      font-size: 12.5px;
      font-weight: 600;
      text-decoration: none;
      margin-top: 18px;
    }
    .back-link:hover { color: #fff; }
  `]
})
export class AdminLoginComponent {
  // Propriétés simples (pas des signals) : [(ngModel)] fait du two-way
  // binding classique dessus, ce que les signals ne supportent pas
  // nativement avec ngModel (il faudrait l'API model() + une syntaxe
  // différente).
  username = '';
  password = '';
  loading = false;
  error: string | null = null;

  constructor(private authService: AuthService, private router: Router) {}

  submit(): void {
    if (!this.username || !this.password) return;
    this.loading = true;
    this.error = null;

    this.authService.login(this.username, this.password).subscribe({
      next: () => {
        this.loading = false;
        this.router.navigate(['/admin']);
      },
      error: (err) => {
        this.loading = false;
        this.error =
          err.status === 401
            ? 'Identifiant ou mot de passe incorrect.'
            : "Impossible de contacter le backend. Vérifie qu'il tourne bien sur http://localhost:8081.";
      }
    });
  }
}
