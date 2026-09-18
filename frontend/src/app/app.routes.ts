import { Routes } from '@angular/router';
import { adminGuard } from './services/admin.guard';

export const routes: Routes = [
  { path: '', loadComponent: () => import('./chat/chat.component').then((m) => m.ChatComponent) },
  // NOUVEAU (sécurité espace admin) : /admin/login n'a pas de guard (il
  // faut bien pouvoir s'y rendre sans être déjà connecté). /admin, lui,
  // est protégé par adminGuard qui redirige vers /admin/login si aucun
  // token valide n'est en session (cf. AuthService).
  { path: 'admin/login', loadComponent: () => import('./admin/admin-login.component').then((m) => m.AdminLoginComponent) },
  {
    path: 'admin',
    canActivate: [adminGuard],
    loadComponent: () => import('./admin/admin-dashboard.component').then((m) => m.AdminDashboardComponent)
  },
  { path: '**', redirectTo: '' }
];
