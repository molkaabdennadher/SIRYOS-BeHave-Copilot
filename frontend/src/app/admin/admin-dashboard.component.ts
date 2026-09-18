import { Component, OnDestroy, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router, RouterLink } from '@angular/router';
import { GuideAdminService } from '../services/guide-admin.service';
import { AuthService } from '../services/auth.service';
import { GuideSummary } from '../shared/models';

/**
 * NOUVEAU (espace admin).
 *
 * Écran d'administration des guides utilisés par le RAG : l'admin
 * dépose un PDF/Word, le backend calcule un hash SHA-256 du contenu
 * (cf. GuideAdminService côté serveur) et :
 *   - si un guide de même nom existe déjà avec le MÊME hash -> rien n'est
 *     refait (pas de chunking, pas d'embedding) ;
 *   - si le hash diffère (contenu modifié) -> anciens chunks supprimés du
 *     vector store, ré-chunking + ré-embedding ;
 *   - si c'est un nouveau nom de fichier -> chunking + embedding initial.
 * La suppression d'un guide retire aussi ses chunks du vector store.
 *
 * Ce composant ne fait qu'appeler ces 4 actions et refléter le statut
 * (PENDING / PROCESSED / FAILED) renvoyé par le backend — toute la
 * logique de décision "faut-il retraiter ?" vit côté serveur, jamais ici.
 */
@Component({
  selector: 'app-admin-dashboard',
  standalone: true,
  imports: [CommonModule, RouterLink],
  template: `
    <div class="admin-page">
      <div class="bg-glow bg-glow-1"></div>
      <div class="bg-glow bg-glow-2"></div>

      <header class="admin-header">
        <div class="admin-brand">
          <div class="logo-shell">
            <img src="assets/behave-copilot-mark.png" alt="Logo BeHave Copilot" class="brand-logo">
          </div>
          <div>
            <span class="eyebrow">Espace administrateur</span>
            <h1>Guides &amp; indexation</h1>
            <p>Ajoutez, mettez à jour ou retirez les guides utilisés par l'assistant.</p>
          </div>
        </div>
        <div class="admin-header-actions">
          <a routerLink="/" class="back-link"><span>←</span> Retour au chat</a>
          <button type="button" class="logout-btn" (click)="logout()"><span>⏻</span> Déconnexion</button>
        </div>
      </header>

      <main class="admin-content">
        <section
          class="dropzone"
          [class.dragover]="dragOver()"
          (dragover)="onDragOver($event)"
          (dragleave)="onDragLeave($event)"
          (drop)="onDrop($event)">
          <input
            #fileInput
            type="file"
            accept=".pdf,.docx,.doc"
            class="hidden-file-input"
            (change)="onFilesSelected($event)"
            multiple>
          <div class="dropzone-icon">⬆</div>
          <p class="dropzone-title">Glissez-déposez un guide ici</p>
          <p class="dropzone-hint">PDF ou Word (.pdf, .docx, .doc) — plusieurs fichiers possibles</p>
          <button type="button" class="browse-btn" (click)="fileInput.click()">Parcourir…</button>
        </section>

        <div class="upload-queue" *ngIf="uploadingFiles().length > 0">
          <div class="upload-row" *ngFor="let u of uploadingFiles()">
            <span class="upload-spinner" *ngIf="u.status === 'uploading'"></span>
            <span class="upload-icon ok" *ngIf="u.status === 'done'">✓</span>
            <span class="upload-icon err" *ngIf="u.status === 'error'">✕</span>
            <span class="upload-name">{{ u.name }}</span>
            <span class="upload-message">{{ u.message }}</span>
          </div>
        </div>

        <div class="toast" *ngIf="toast()" [class.error]="toast()?.error">{{ toast()?.text }}</div>

        <section class="guides-panel">
          <div class="panel-head">
            <h2>Guides indexés <span class="count-badge">{{ guides().length }}</span></h2>
            <button type="button" class="refresh-btn" (click)="refresh()" [disabled]="loading()">
              <span [class.spin]="loading()">⟳</span> Actualiser
            </button>
          </div>

          <p class="panel-empty" *ngIf="!loading() && guides().length === 0">
            Aucun guide indexé pour l'instant. Déposez un PDF ou un Word ci-dessus pour démarrer.
          </p>

          <div class="guides-table" *ngIf="guides().length > 0">
            <div class="table-row table-head">
              <span>Fichier</span>
              <span>Statut</span>
              <span>Chunks</span>
              <span>Hash</span>
              <span>Dernière indexation</span>
              <span>Actions</span>
            </div>
            <div class="table-row" *ngFor="let g of guides()">
              <span class="cell-filename" [title]="g.filename">{{ g.filename }}</span>
              <span>
                <span class="status-badge" [ngClass]="'status-' + g.status.toLowerCase()">
                  {{ statusLabel(g.status) }}
                </span>
                <span class="error-hint" *ngIf="g.status === 'FAILED' && g.lastError" [title]="g.lastError">⚠</span>
              </span>
              <span>{{ g.chunkCount }}</span>
              <span class="cell-hash">{{ g.contentHashShort }}</span>
              <span class="cell-date">{{ g.lastProcessedAt ? (g.lastProcessedAt | date: 'dd/MM/yyyy HH:mm') : '—' }}</span>
              <span class="cell-actions">
                <button type="button" title="Forcer une ré-indexation" (click)="reprocess(g)" [disabled]="busyIds().has(g.id)">⟲</button>
                <button type="button" class="danger" title="Supprimer ce guide" (click)="remove(g)" [disabled]="busyIds().has(g.id)">🗑</button>
              </span>
            </div>
          </div>
        </section>
      </main>
    </div>
  `,
  styles: [`
    :host { display: block; font-family: 'Inter', system-ui, sans-serif; }

    .admin-page {
      position: relative;
      min-height: 100vh;
      background: #0c1130;
      overflow: hidden;
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

    .admin-header {
      position: relative;
      z-index: 1;
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 26px 40px;
      background: rgba(18, 24, 63, 0.65);
      backdrop-filter: blur(14px);
      border-bottom: 1px solid rgba(255,255,255,0.08);
      flex-wrap: wrap;
      gap: 14px;
    }

    .admin-brand { display: flex; align-items: center; gap: 14px; }

    .logo-shell {
      width: 46px;
      height: 46px;
      border-radius: 13px;
      overflow: hidden;
      flex-shrink: 0;
      background: rgba(255,255,255,0.06);
      display: flex;
      align-items: center;
      justify-content: center;
    }
    .brand-logo { width: 100%; height: 100%; object-fit: cover; }

    .eyebrow { color: #ef8b3e; font-size: 11.5px; font-weight: 700; letter-spacing: 0.06em; text-transform: uppercase; }
    .admin-brand h1 { color: #fff; font-size: 20px; font-weight: 700; margin: 4px 0 0; letter-spacing: -0.01em; }
    .admin-brand p { color: #9aa4d4; font-size: 12.5px; margin: 3px 0 0; }

    .back-link {
      display: flex;
      align-items: center;
      gap: 6px;
      color: #c4cbef;
      font-size: 13px;
      font-weight: 600;
      text-decoration: none;
      border: 1px solid rgba(255,255,255,0.14);
      padding: 9px 16px;
      border-radius: 10px;
      transition: background 0.2s, color 0.2s;
    }
    .back-link:hover { background: rgba(255,255,255,0.08); color: #fff; }

    .admin-header-actions { display: flex; align-items: center; gap: 10px; }

    .logout-btn {
      display: flex;
      align-items: center;
      gap: 6px;
      color: #f2a6a6;
      font-size: 13px;
      font-weight: 600;
      background: rgba(248,113,113,0.08);
      border: 1px solid rgba(248,113,113,0.25);
      padding: 9px 16px;
      border-radius: 10px;
      cursor: pointer;
      transition: background 0.2s, color 0.2s;
    }
    .logout-btn:hover { background: rgba(248,113,113,0.18); color: #fff; }

    .admin-content {
      position: relative;
      z-index: 1;
      max-width: 980px;
      margin: 0 auto;
      padding: 32px 24px 60px;
      display: flex;
      flex-direction: column;
      gap: 22px;
    }

    .dropzone {
      border: 2px dashed rgba(255,255,255,0.18);
      border-radius: 18px;
      padding: 40px 24px;
      text-align: center;
      background: rgba(255,255,255,0.03);
      transition: border-color 0.2s, background 0.2s;
    }
    .dropzone.dragover { border-color: #ef8b3e; background: rgba(239,139,62,0.08); }

    .dropzone-icon { font-size: 26px; color: #ef8b3e; margin-bottom: 6px; }
    .dropzone-title { color: #fff; font-size: 15px; font-weight: 700; margin: 0; }
    .dropzone-hint { color: #9aa4d4; font-size: 12.5px; margin: 6px 0 16px; }
    .hidden-file-input { display: none; }

    .browse-btn {
      border: none;
      background: linear-gradient(135deg, #ef8b3e, #f2a663);
      color: #1a2456;
      font-weight: 700;
      font-size: 13px;
      padding: 10px 22px;
      border-radius: 10px;
      cursor: pointer;
      box-shadow: 0 6px 18px rgba(239,139,62,0.35);
    }
    .browse-btn:hover { filter: brightness(1.05); }

    .upload-queue { display: flex; flex-direction: column; gap: 8px; }

    .upload-row {
      display: flex;
      align-items: center;
      gap: 10px;
      background: rgba(255,255,255,0.05);
      border-radius: 10px;
      padding: 10px 14px;
      color: #c4cbef;
      font-size: 12.5px;
    }

    .upload-spinner {
      width: 14px; height: 14px;
      border: 2px solid rgba(255,255,255,0.25);
      border-top-color: #ef8b3e;
      border-radius: 50%;
      animation: spin 0.8s linear infinite;
      flex-shrink: 0;
    }
    .upload-icon { width: 14px; text-align: center; flex-shrink: 0; }
    .upload-icon.ok { color: #4ade80; }
    .upload-icon.err { color: #f87171; }
    .upload-name { font-weight: 700; color: #fff; }
    .upload-message { color: #9aa4d4; margin-left: auto; text-align: right; }

    @keyframes spin { to { transform: rotate(360deg); } }

    .toast {
      background: rgba(74, 222, 128, 0.12);
      border: 1px solid rgba(74, 222, 128, 0.35);
      color: #bbf7d0;
      font-size: 12.5px;
      font-weight: 600;
      padding: 10px 16px;
      border-radius: 10px;
    }
    .toast.error {
      background: rgba(248, 113, 113, 0.12);
      border-color: rgba(248, 113, 113, 0.35);
      color: #fecaca;
    }

    .guides-panel {
      background: rgba(255,255,255,0.03);
      border: 1px solid rgba(255,255,255,0.08);
      border-radius: 16px;
      padding: 20px;
    }

    .panel-head {
      display: flex;
      align-items: center;
      justify-content: space-between;
      margin-bottom: 14px;
    }
    .panel-head h2 { color: #fff; font-size: 15px; font-weight: 700; margin: 0; display: flex; align-items: center; gap: 8px; }
    .count-badge {
      background: rgba(239,139,62,0.18);
      color: #ef8b3e;
      font-size: 11.5px;
      font-weight: 700;
      padding: 2px 8px;
      border-radius: 999px;
    }

    .refresh-btn {
      display: flex;
      align-items: center;
      gap: 6px;
      border: 1px solid rgba(255,255,255,0.14);
      background: transparent;
      color: #c4cbef;
      font-size: 12.5px;
      font-weight: 600;
      padding: 7px 14px;
      border-radius: 9px;
      cursor: pointer;
    }
    .refresh-btn:hover:not(:disabled) { background: rgba(255,255,255,0.06); color: #fff; }
    .refresh-btn:disabled { opacity: 0.6; cursor: not-allowed; }
    .refresh-btn .spin { display: inline-block; animation: spin 1s linear infinite; }

    .panel-empty { color: #7b84b5; font-size: 13px; text-align: center; padding: 20px 0; }

    .guides-table { display: flex; flex-direction: column; gap: 2px; }

    .table-row {
      display: grid;
      grid-template-columns: 2.2fr 1.1fr 0.7fr 0.9fr 1.3fr 0.9fr;
      align-items: center;
      gap: 10px;
      padding: 11px 10px;
      border-radius: 9px;
      font-size: 12.5px;
      color: #c4cbef;
    }
    .table-row:not(.table-head):hover { background: rgba(255,255,255,0.04); }

    .table-head {
      color: #7b84b5;
      font-size: 11px;
      font-weight: 700;
      text-transform: uppercase;
      letter-spacing: 0.04em;
      border-bottom: 1px solid rgba(255,255,255,0.08);
      border-radius: 0;
    }

    .cell-filename { color: #fff; font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    .cell-hash { font-family: ui-monospace, monospace; color: #7b84b5; }
    .cell-date { color: #9aa4d4; }

    .status-badge {
      display: inline-block;
      font-size: 11px;
      font-weight: 700;
      padding: 3px 9px;
      border-radius: 999px;
      text-transform: uppercase;
      letter-spacing: 0.02em;
    }
    .status-processed { background: rgba(74,222,128,0.15); color: #4ade80; }
    .status-pending { background: rgba(250,204,21,0.15); color: #facc15; }
    .status-failed { background: rgba(248,113,113,0.15); color: #f87171; }
    .error-hint { margin-left: 6px; cursor: help; }

    .cell-actions { display: flex; gap: 6px; justify-content: flex-end; }
    .cell-actions button {
      border: 1px solid rgba(255,255,255,0.14);
      background: rgba(255,255,255,0.04);
      color: #c4cbef;
      width: 30px;
      height: 30px;
      border-radius: 8px;
      cursor: pointer;
      font-size: 13px;
    }
    .cell-actions button:hover:not(:disabled) { background: rgba(255,255,255,0.1); color: #fff; }
    .cell-actions button:disabled { opacity: 0.5; cursor: not-allowed; }
    .cell-actions button.danger:hover:not(:disabled) { background: rgba(248,113,113,0.18); color: #f87171; }

    @media (max-width: 760px) {
      .table-row { grid-template-columns: 1.6fr 1fr 1fr; }
      .table-row span:nth-child(4), .table-row span:nth-child(5) { display: none; }
    }
  `]
})
export class AdminDashboardComponent implements OnInit, OnDestroy {
  readonly guides = signal<GuideSummary[]>([]);
  readonly loading = signal(false);
  readonly dragOver = signal(false);
  readonly busyIds = signal<Set<string>>(new Set());
  readonly uploadingFiles = signal<{ name: string; status: 'uploading' | 'done' | 'error'; message: string }[]>([]);
  readonly toast = signal<{ text: string; error: boolean } | null>(null);

  private toastTimer?: ReturnType<typeof setTimeout>;

  constructor(
    private guideAdminService: GuideAdminService,
    private authService: AuthService,
    private router: Router
  ) {}

  ngOnInit(): void {
    this.refresh();
  }

  logout(): void {
    this.authService.logout();
    this.router.navigate(['/admin/login']);
  }

  ngOnDestroy(): void {
    if (this.toastTimer) clearTimeout(this.toastTimer);
  }

  refresh(): void {
    this.loading.set(true);
    this.guideAdminService.listGuides().subscribe({
      next: (guides) => {
        this.guides.set(guides);
        this.loading.set(false);
      },
      error: () => {
        this.loading.set(false);
        this.showToast("Impossible de contacter le backend. Vérifie qu'il tourne bien sur http://localhost:8081.", true);
      }
    });
  }

  statusLabel(status: string): string {
    switch (status) {
      case 'PROCESSED': return 'Indexé';
      case 'PENDING': return 'En cours';
      case 'FAILED': return 'Échec';
      default: return status;
    }
  }

  onDragOver(event: DragEvent): void {
    event.preventDefault();
    this.dragOver.set(true);
  }

  onDragLeave(event: DragEvent): void {
    event.preventDefault();
    this.dragOver.set(false);
  }

  onDrop(event: DragEvent): void {
    event.preventDefault();
    this.dragOver.set(false);
    const files = event.dataTransfer?.files;
    if (files && files.length > 0) {
      this.uploadFiles(Array.from(files));
    }
  }

  onFilesSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    const files = input.files ? Array.from(input.files) : [];
    input.value = ''; // permet de re-sélectionner le(s) même(s) fichier(s) ensuite
    if (files.length > 0) {
      this.uploadFiles(files);
    }
  }

  /**
   * Upload un ou plusieurs guides. La règle "on ne refait rien si le
   * contenu n'a pas changé" est décidée intégralement côté backend
   * (comparaison de hash) — ce composant se contente d'afficher le
   * résultat renvoyé (statut, nombre de chunks) pour chaque fichier.
   */
  private uploadFiles(files: File[]): void {
    const accepted = files.filter((f) => /\.(pdf|docx?|DOCX?|PDF)$/.test(f.name));
    const rejected = files.filter((f) => !accepted.includes(f));
    rejected.forEach((f) => this.showToast(`"${f.name}" ignoré : formats acceptés = PDF, DOCX, DOC.`, true));

    accepted.forEach((file) => {
      this.uploadingFiles.update((list) => [...list, { name: file.name, status: 'uploading', message: 'Envoi en cours…' }]);

      const before = this.guides().find((g) => g.filename === file.name);

      this.guideAdminService.upload(file).subscribe({
        next: (summary) => {
          const unchanged = !!before && before.contentHashShort === summary.contentHashShort
            && before.lastProcessedAt === summary.lastProcessedAt;
          const message = unchanged
            ? 'Aucun changement — embedding non refait'
            : summary.status === 'FAILED'
              ? 'Échec du traitement'
              : before
                ? 'Modifié — ré-indexé'
                : 'Nouveau guide indexé';

          this.updateUploadRow(file.name, summary.status === 'FAILED' ? 'error' : 'done', message);
          this.upsertGuide(summary);
        },
        error: (err) => {
          this.updateUploadRow(file.name, 'error', `Erreur (${err.status || 'réseau'})`);
        }
      });
    });
  }

  private updateUploadRow(name: string, status: 'done' | 'error', message: string): void {
    this.uploadingFiles.update((list) => list.map((u) => (u.name === name ? { ...u, status, message } : u)));
  }

  private upsertGuide(summary: GuideSummary): void {
    this.guides.update((list) => {
      const idx = list.findIndex((g) => g.id === summary.id);
      if (idx === -1) return [...list, summary].sort((a, b) => a.filename.localeCompare(b.filename));
      const next = [...list];
      next[idx] = summary;
      return next;
    });
  }

  reprocess(guide: GuideSummary): void {
    this.setBusy(guide.id, true);
    this.guideAdminService.reprocess(guide.id).subscribe({
      next: (summary) => {
        this.upsertGuide(summary);
        this.setBusy(guide.id, false);
        this.showToast(`"${guide.filename}" ré-indexé (${summary.chunkCount} chunks).`, summary.status === 'FAILED');
      },
      error: () => {
        this.setBusy(guide.id, false);
        this.showToast(`Échec de la ré-indexation de "${guide.filename}".`, true);
      }
    });
  }

  remove(guide: GuideSummary): void {
    if (!confirm(`Supprimer définitivement "${guide.filename}" ? Ses passages ne seront plus utilisés par l'assistant.`)) {
      return;
    }
    this.setBusy(guide.id, true);
    this.guideAdminService.deleteGuide(guide.id).subscribe({
      next: () => {
        this.guides.update((list) => list.filter((g) => g.id !== guide.id));
        this.setBusy(guide.id, false);
        this.showToast(`"${guide.filename}" supprimé.`, false);
      },
      error: () => {
        this.setBusy(guide.id, false);
        this.showToast(`Échec de la suppression de "${guide.filename}".`, true);
      }
    });
  }

  private setBusy(id: string, busy: boolean): void {
    this.busyIds.update((set) => {
      const next = new Set(set);
      busy ? next.add(id) : next.delete(id);
      return next;
    });
  }

  private showToast(text: string, error: boolean): void {
    this.toast.set({ text, error });
    if (this.toastTimer) clearTimeout(this.toastTimer);
    this.toastTimer = setTimeout(() => this.toast.set(null), 5000);
  }
}
