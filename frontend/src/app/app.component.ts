import { Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';

/**
 * MODIFIÉ : AppComponent n'est plus la page de chat elle-même — c'est
 * désormais une coquille légère qui héberge le routeur. La page de chat
 * (ancien contenu de ce fichier) a été déplacée telle quelle vers
 * ChatComponent (`chat/chat.component.ts`), et un nouveau
 * AdminDashboardComponent (`admin/admin-dashboard.component.ts`) a été
 * ajouté pour l'espace administrateur (§ gestion des guides).
 */
@Component({
  selector: 'app-root',
  standalone: true,
  imports: [RouterOutlet],
  template: `<router-outlet></router-outlet>`
})
export class AppComponent {}
