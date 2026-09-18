import { Component, ElementRef, ViewChild, AfterViewChecked, OnDestroy, OnInit, HostListener, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { RouterLink } from '@angular/router';
import { ChatHistoryService } from '../services/chat-history.service';
import { ChatSessionSummary, ChatMessageView } from '../shared/models';

type ExplanationLevel = 'SIMPLE' | 'DETAILLE' | 'TECHNIQUE';
type Language = 'FR' | 'EN' | 'AR';

interface SourceReference {
  sentenceId: string;
  filename: string;
  page: number;
  excerpt: string;
  documentId: string;
  pageLabel: string;
  sourceUrl: string;
  viewablePage: boolean;
  section?: string;
  score?: number;
}

interface ImageReference {
  documentId: string;
  fileName: string;
  page: number;
  url: string;
  section?: string;
  explanation?: string;
}

interface ChatResponse {
  answer: string;
  sources: SourceReference[];
  language: Language;
  images?: ImageReference[];
  /** NOUVEAU (historique) : toujours renseigné par le backend, cf. ChatController. */
  sessionId?: string;
}

interface ChatMessage {
  role: 'user' | 'bot';
  text: string;
  sources?: SourceReference[];
  images?: ImageReference[];
  language?: Language;
  error?: boolean;
  /** Aperçu (URL locale) de l'image envoyée par l'utilisateur, le cas échéant. */
  attachedImagePreviewUrl?: string;
}

interface HistoryTurn {
  role: 'user' | 'assistant';
  text: string;
}

@Component({
  selector: 'app-chat',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink],
  template: `
    <div class="app-shell">
      <aside class="sidebar" [class.open]="sidebarOpen()">
        <div class="sidebar-header">
          <button type="button" class="sidebar-new-btn" (click)="newConversation()" [disabled]="loading()">
            <span>＋</span> Nouvelle conversation
          </button>
          <button type="button" class="sidebar-collapse" (click)="sidebarOpen.set(false)" aria-label="Fermer l'historique">✕</button>
        </div>
        <div class="sidebar-list">
          <p class="sidebar-empty" *ngIf="sessions().length === 0">Aucune conversation pour l'instant.</p>
          <button
            type="button"
            class="sidebar-item"
            *ngFor="let s of sessions()"
            [class.active]="s.id === currentSessionId()"
            (click)="loadSession(s)">
            <span class="sidebar-item-title">{{ s.title }}</span>
            <span class="sidebar-item-date">{{ s.updatedAt | date: 'dd/MM HH:mm' }}</span>
            <span class="sidebar-item-delete" title="Supprimer cette conversation" (click)="deleteSession(s, $event)">🗑</span>
          </button>
        </div>
        <a class="sidebar-admin-link" routerLink="/admin">
          <span>⚙</span> Espace administrateur
        </a>
      </aside>

      <div class="sidebar-scrim" *ngIf="sidebarOpen()" (click)="sidebarOpen.set(false)"></div>

      <div class="page">
      <div class="bg-glow bg-glow-1"></div>
      <div class="bg-glow bg-glow-2"></div>
      <div
        class="siryos-cursor-halo"
        [class.visible]="cursorVisible()"
        [class.interacting]="cursorInteracting()"
        [style.transform]="cursorTransform()"></div>
      <div
        class="siryos-cursor"
        [class.visible]="cursorVisible()"
        [class.interacting]="cursorInteracting()"
        [class.thinking]="loading()"
        [style.transform]="cursorTransform()"
        aria-hidden="true">
        <img src="assets/behave-copilot-mark.png" alt="">
      </div>

      <header class="header">
        <div class="brand">
          <button type="button" class="history-toggle" (click)="sidebarOpen.set(!sidebarOpen())" title="Historique des conversations" aria-label="Historique des conversations">☰</button>
          <div class="logo-shell">
            <img src="assets/behave-copilot-mark.png" alt="Logo BeHave Copilot" class="brand-logo">
          </div>
          <div>
            <span class="eyebrow">Assistant intelligent</span>
            <h1>BeHave Copilot</h1>
            <p>Vos guides BeHave, expliqués avec précision.</p>
          </div>
          <span class="status-pill"><span></span> En ligne</span>
        </div>
        <div class="mode-section">
          <div class="mode-heading">
            <span>Choisissez le niveau de réponse</span>
            <span class="mode-hint">Vous pouvez le changer à tout moment</span>
          </div>
          <div class="level-picker" role="radiogroup" aria-label="Niveau de réponse">
            <div class="level-option" *ngFor="let opt of levels">
              <button
                type="button"
                role="radio"
                [attr.aria-checked]="level() === opt.value"
                [attr.aria-label]="opt.label + ' : ' + opt.description"
                [class.active]="level() === opt.value"
                (click)="level.set(opt.value)">
                <span class="level-icon">{{ opt.icon }}</span>
                <span class="level-copy">
                  <span class="level-title">{{ opt.label }}</span>
                  <span class="level-tier">{{ opt.tier }}</span>
                </span>
              </button>
              <button type="button" class="info-button" [attr.aria-label]="'Informations sur le mode ' + opt.label">
                <span aria-hidden="true">i</span>
                <span class="mode-tooltip" role="tooltip">
                  <strong>{{ opt.label }}</strong>
                  <span>{{ opt.description }}</span>
                  <em>{{ opt.tierLong }}</em>
                </span>
              </button>
            </div>
          </div>
          <div class="smart-toolbar">
            <span class="active-mode-indicator">
              <span class="indicator-dot"></span>
              Mode {{ activeLevelLabel() }} activé
            </span>
            <div class="toolbar-actions">
              <button type="button" (click)="focusComposer()" title="Écrire une question">
                <span>⌘</span> Écrire <kbd>Ctrl K</kbd>
              </button>
              <button type="button" (click)="copyLastAnswer()" [disabled]="!lastBotAnswer()" title="Copier la dernière réponse">
                <span>▣</span> {{ copied() ? 'Copiée !' : 'Copier' }}
              </button>
              <button type="button" (click)="newConversation()" [disabled]="messages().length === 0 || loading()" title="Démarrer une nouvelle conversation">
                <span>＋</span> Nouveau chat
              </button>
            </div>
          </div>
        </div>
      </header>

      <main class="chat" #scrollAnchor>
        <div class="empty" *ngIf="messages().length === 0">
          <div class="empty-badge">
            <img src="assets/behave-copilot-mark.png" alt="" class="empty-logo">
          </div>
          <span class="welcome-kicker">Bienvenue sur BeHave Copilot</span>
          <h2>Comment puis-je vous aider ?</h2>
          <p>Interrogez vos guides BeHave Access, Predictive ou Analytics. L’assistant retrouve le bon contexte, les sources et les illustrations utiles.</p>
          <div class="suggestions">
            <button (click)="askSuggestion('Comment interpréter un score de risque SoD dans BeHave Access ?')">
              <span class="chip access">Access</span> Score de risque SoD ?
            </button>
            <button (click)="askSuggestion('How does sales forecasting work in BeHave Predictive?')">
              <span class="chip predictive">Predictive</span> Sales forecasting?
            </button>
            <button (click)="askSuggestion('Quels sont les KPIs disponibles dans BeHave Analytics ?')">
              <span class="chip analytics">Analytics</span> KPIs disponibles ?
            </button>
          </div>
        </div>

        <div class="message" *ngFor="let msg of messages()" [class.user]="msg.role === 'user'" [class.bot]="msg.role === 'bot'">
          <div class="avatar bot-avatar" *ngIf="msg.role === 'bot'">
            <img src="assets/behave-copilot-mark.png" alt="BeHave Copilot">
          </div>

          <div class="bubble" [class.error]="msg.error" [dir]="msg.language === 'AR' ? 'rtl' : 'ltr'">
            <div class="lang-tag" *ngIf="msg.language && msg.language !== 'FR'">{{ msg.language }}</div>

            <img
              *ngIf="msg.attachedImagePreviewUrl"
              class="attached-image"
              [src]="msg.attachedImagePreviewUrl"
              alt="Image envoyée">

            <p *ngIf="msg.text">{{ msg.text }}</p>

            <div class="guide-images" *ngIf="msg.images && msg.images.length > 0">
              <span class="guide-images-label">
                <span class="sources-icon">🖼️</span> {{ guideImagesLabel(msg.language) }}
              </span>
              <div class="guide-images-grid">
                <a
                  class="guide-image"
                  *ngFor="let img of msg.images"
                  [href]="apiOrigin + img.url"
                  target="_blank"
                  rel="noopener"
                  [title]="img.documentId + ' — Page ' + img.page">
                  <img [src]="apiOrigin + img.url" [alt]="img.documentId">
                  <span class="guide-image-page">p. {{ img.page }}</span>
                  <span class="guide-image-explain" *ngIf="img.explanation">{{ img.explanation }}</span>
                </a>
              </div>
            </div>

            <div class="sources" *ngIf="msg.sources && msg.sources.length > 0">
              <span class="sources-label">
                <span class="sources-icon">📎</span> {{ sourcesLabel(msg.language) }}
              </span>
              <div
                class="source"
                *ngFor="let src of msg.sources"
                [class.clickable]="src.viewablePage"
                [class.locked]="!src.viewablePage"
                (click)="openSource(src)">
                <div class="source-head">
                  <span class="source-doc">{{ src.filename }} — {{ src.pageLabel }}<span *ngIf="src.section"> · {{ src.section }}</span></span>
                  <span class="source-icon">{{ src.viewablePage ? '↗' : '🔒' }}</span>
                </div>
                <span class="source-excerpt">{{ src.excerpt }}</span>
                <span class="source-score" *ngIf="src.score !== undefined">{{ (src.score * 100) | number:'1.0-0' }}% pertinence</span>
              </div>
            </div>
          </div>

          <div class="avatar user-avatar" *ngIf="msg.role === 'user'" title="Vous" aria-label="Vous">
            <svg viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg" aria-hidden="true">
              <circle cx="12" cy="8" r="4" fill="currentColor"/>
              <path d="M4 20c0-4.4 3.6-8 8-8s8 3.6 8 8" stroke="currentColor" stroke-width="2" stroke-linecap="round" fill="none"/>
            </svg>
          </div>
        </div>

        <div class="message bot" *ngIf="loading()">
          <div class="avatar bot-avatar pulse">
            <img src="assets/behave-copilot-mark.png" alt="BeHave Copilot">
          </div>
          <div class="bubble thinking-bubble">
            <div class="thinking-step">
              <span class="thinking-dots"><span></span><span></span><span></span></span>
              <span class="thinking-text">{{ thinkingSteps[thinkingStepIndex()] }}</span>
            </div>
          </div>
        </div>
      </main>

      <div class="attach-preview" *ngIf="selectedImagePreviewUrl()">
        <img [src]="selectedImagePreviewUrl()" alt="Image sélectionnée">
        <span class="attach-preview-name">{{ selectedImage()?.name }}</span>
        <button type="button" class="attach-remove" (click)="removeSelectedImage()" [disabled]="loading()">✕</button>
      </div>

      <form class="composer" (ngSubmit)="send()">
        <input
          #fileInput
          type="file"
          accept="image/*"
          class="hidden-file-input"
          (change)="onImageSelected($event)"
          [disabled]="loading()">
        <button
          type="button"
          class="attach-btn"
          title="Joindre une image"
          [disabled]="loading()"
          (click)="fileInput.click()">📎</button>
        <button
          type="button"
          class="mic-btn"
          [class.recording]="isRecording()"
          [title]="micTitle()"
          [disabled]="loading() || !speechSupported"
          (click)="toggleRecording()">{{ isRecording() ? '⏹️' : '🎤' }}</button>
        <span class="mic-error" *ngIf="micError()">{{ micError() }}</span>
        <input
          #questionInput
          type="text"
          [(ngModel)]="question"
          name="question"
          [placeholder]="selectedImage() ? 'Décris ce que tu veux savoir sur cette image (optionnel)…' : 'Écris ta question sur BeHave…'"
          [disabled]="loading()"
          autocomplete="off">
        <button type="submit" [disabled]="loading() || !canSend()">
          <span *ngIf="!loading()">Envoyer</span>
          <span *ngIf="loading()" class="send-spinner"></span>
        </button>
        <span class="composer-status" aria-live="polite">
          <span class="composer-mode">{{ activeLevelLabel() }}</span>
          <span>{{ question.length }} caractères</span>
        </span>
      </form>
      </div>
    </div>
  `,
  styles: [`
    :host {
      display: block;
      height: 100vh;
      font-family: 'Inter', system-ui, sans-serif;
    }

    /* NOUVEAU : coquille app-shell = sidebar historique + page de chat.
       La page de chat garde exactement sa largeur/centrage d'origine ;
       la sidebar se superpose en tiroir (comme ChatGPT) plutôt que de
       pousser le contenu, pour ne rien casser du design existant. */
    .app-shell {
      position: relative;
      display: flex;
      height: 100vh;
      background: #0c1130;
    }

    .history-toggle {
      border: none;
      background: rgba(255,255,255,0.08);
      color: #fff;
      width: 34px;
      height: 34px;
      border-radius: 9px;
      font-size: 15px;
      cursor: pointer;
      flex-shrink: 0;
      transition: background 0.2s;
    }
    .history-toggle:hover { background: rgba(255,255,255,0.16); }

    .sidebar-scrim {
      position: fixed;
      inset: 0;
      background: rgba(4,6,20,0.55);
      z-index: 29;
    }
      @media (min-width: 901px) {
  .sidebar-scrim { display: none; }
}

    .sidebar {
  position: relative;
  width: 300px;
  max-width: 82vw;
  background: #10143a;
  border-right: 1px solid rgba(255,255,255,0.08);
  display: flex;
  flex-direction: column;
  z-index: 30;
  flex-shrink: 0;
  margin-left: -300px;
  transition: margin-left 0.28s cubic-bezier(0.4, 0, 0.2, 1);
}
.sidebar.open { margin-left: 0; }

@media (max-width: 900px) {
  .sidebar {
    position: fixed;
    top: 0; left: 0; bottom: 0;
    margin-left: 0;
    transform: translateX(-100%);
    transition: transform 0.28s cubic-bezier(0.4, 0, 0.2, 1);
  }
  .sidebar.open { transform: translateX(0); }
}
    .sidebar.open { transform: translateX(0); }

    .sidebar-header {
      display: flex;
      align-items: center;
      gap: 8px;
      padding: 16px;
      border-bottom: 1px solid rgba(255,255,255,0.08);
    }

    .sidebar-new-btn {
      flex: 1;
      display: flex;
      align-items: center;
      gap: 8px;
      justify-content: center;
      border: 1px solid rgba(255,255,255,0.14);
      background: rgba(255,255,255,0.05);
      color: #fff;
      font-weight: 600;
      font-size: 13px;
      padding: 10px 12px;
      border-radius: 10px;
      cursor: pointer;
      transition: background 0.2s;
    }
    .sidebar-new-btn:hover:not(:disabled) { background: rgba(255,255,255,0.1); }
    .sidebar-new-btn:disabled { opacity: 0.5; cursor: not-allowed; }

    .sidebar-collapse {
      border: none;
      background: transparent;
      color: #9aa4d4;
      width: 32px;
      height: 32px;
      border-radius: 8px;
      cursor: pointer;
      flex-shrink: 0;
    }
    .sidebar-collapse:hover { background: rgba(255,255,255,0.08); color: #fff; }

    .sidebar-list {
      flex: 1;
      overflow-y: auto;
      padding: 10px;
      display: flex;
      flex-direction: column;
      gap: 4px;
    }

    .sidebar-empty {
      color: #6f79ab;
      font-size: 12.5px;
      text-align: center;
      margin-top: 20px;
      padding: 0 12px;
    }

    .sidebar-item {
      position: relative;
      display: flex;
      flex-direction: column;
      align-items: flex-start;
      gap: 2px;
      width: 100%;
      text-align: left;
      border: none;
      background: transparent;
      color: #c4cbef;
      padding: 10px 34px 10px 12px;
      border-radius: 9px;
      cursor: pointer;
      font-family: inherit;
    }
    .sidebar-item:hover { background: rgba(255,255,255,0.06); }
    .sidebar-item.active { background: rgba(239,139,62,0.16); color: #fff; }

    .sidebar-item-title {
      font-size: 13px;
      font-weight: 600;
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
      max-width: 100%;
    }

    .sidebar-item-date {
      font-size: 11px;
      color: #7b84b5;
    }

    .sidebar-item-delete {
      position: absolute;
      right: 8px;
      top: 50%;
      transform: translateY(-50%);
      font-size: 13px;
      opacity: 0;
      padding: 4px;
      border-radius: 6px;
      transition: opacity 0.15s;
    }
    .sidebar-item:hover .sidebar-item-delete { opacity: 0.7; }
    .sidebar-item-delete:hover { opacity: 1 !important; background: rgba(255,255,255,0.12); }

    .sidebar-admin-link {
      display: flex;
      align-items: center;
      gap: 8px;
      padding: 14px 16px;
      border-top: 1px solid rgba(255,255,255,0.08);
      color: #9aa4d4;
      font-size: 12.5px;
      font-weight: 600;
      text-decoration: none;
      transition: color 0.2s;
    }
    .sidebar-admin-link:hover { color: #ef8b3e; }

    .page {
      position: relative;
      display: flex;
      flex-direction: column;
      height: 100vh;
      flex: 1;
      width: 100%;
      background: #0c1130;
      box-shadow: 0 0 80px rgba(0,0,0,0.5);
      overflow: hidden;
    }

    .bg-glow {
      position: absolute;
      border-radius: 50%;
      filter: blur(90px);
      opacity: 0.35;
      pointer-events: none;
      z-index: 0;
      animation: drift 14s ease-in-out infinite alternate;
    }

    .bg-glow-1 {
      width: 380px;
      height: 380px;
      background: #ef8b3e;
      top: -120px;
      left: -80px;
    }

    .bg-glow-2 {
      width: 320px;
      height: 320px;
      background: #5865f2;
      bottom: -100px;
      right: -60px;
      animation-delay: -6s;
    }

    @keyframes drift {
      from { transform: translate(0, 0) scale(1); }
      to { transform: translate(30px, 25px) scale(1.08); }
    }

    .header {
      position: relative;
      z-index: 1;
      padding: 22px 28px;
      background: rgba(18, 24, 63, 0.65);
      backdrop-filter: blur(14px);
      border-bottom: 1px solid rgba(255,255,255,0.08);
      display: flex;
      flex-direction: column;
      gap: 16px;
    }

    .brand {
      display: flex;
      align-items: center;
      gap: 14px;
    }

    .logo {
      width: 42px;
      height: 42px;
      border-radius: 13px;
      background: linear-gradient(135deg, #ef8b3e, #ffb066);
      color: #1a2456;
      font-weight: 800;
      font-size: 19px;
      display: flex;
      align-items: center;
      justify-content: center;
      flex-shrink: 0;
      box-shadow: 0 6px 18px rgba(239,139,62,0.4);
    }

    .brand h1 {
      color: #fff;
      font-size: 17.5px;
      font-weight: 700;
      margin: 0;
      letter-spacing: -0.01em;
    }

    .brand p {
      color: #9aa4d4;
      font-size: 12.5px;
      margin: 3px 0 0;
    }

    .level-picker {
      display: flex;
      gap: 4px;
      background: rgba(255,255,255,0.06);
      padding: 4px;
      border-radius: 11px;
      width: fit-content;
    }

    .level-picker button {
      border: none;
      background: transparent;
      color: #9aa4d4;
      font-size: 12.5px;
      font-weight: 600;
      padding: 8px 16px;
      border-radius: 8px;
      cursor: pointer;
      transition: all 0.2s cubic-bezier(0.4, 0, 0.2, 1);
    }

    .level-picker button.active {
      background: linear-gradient(135deg, #ef8b3e, #f2a663);
      color: #1a2456;
      box-shadow: 0 3px 10px rgba(239,139,62,0.35);
    }

    .level-picker button:not(.active):hover {
      color: #fff;
      background: rgba(255,255,255,0.05);
    }

    .chat {
      position: relative;
      z-index: 1;
      flex: 1;
      overflow-y: auto;
      padding: 26px 28px;
      display: flex;
      flex-direction: column;
      gap: 18px;
    }

    .empty {
      margin: auto;
      text-align: center;
      color: #7d87c2;
      max-width: 400px;
    }

    .empty-badge {
      width: 56px;
      height: 56px;
      margin: 0 auto 16px;
      border-radius: 16px;
      background: linear-gradient(135deg, rgba(239,139,62,0.2), rgba(88,101,242,0.2));
      display: flex;
      align-items: center;
      justify-content: center;
      border: 1px solid rgba(239,139,62,0.3);
    }

    .empty-icon {
      font-size: 22px;
      color: #ef8b3e;
    }

    .empty-logo {
      width: 42px;
      height: 42px;
      object-fit: contain;
      animation: logoFloat 3.2s ease-in-out infinite;
    }

    @keyframes logoFloat {
      50% { transform: translateY(-4px) rotate(2deg); }
    }

    .empty h2 {
      color: #fff;
      font-size: 19px;
      margin: 0 0 8px;
    }

    .empty p {
      font-size: 13.5px;
      margin-bottom: 22px;
      line-height: 1.5;
    }

    .suggestions {
      display: flex;
      flex-direction: column;
      gap: 9px;
      align-items: stretch;
    }

    .suggestions button {
      display: flex;
      align-items: center;
      gap: 9px;
      background: rgba(255,255,255,0.05);
      border: 1px solid rgba(255,255,255,0.1);
      color: #c4cbef;
      padding: 11px 15px;
      border-radius: 12px;
      font-size: 13px;
      cursor: pointer;
      transition: all 0.2s ease;
      text-align: left;
    }

    .suggestions button:hover {
      background: rgba(239,139,62,0.12);
      border-color: rgba(239,139,62,0.4);
      color: #fff;
      transform: translateX(2px);
    }

    .chip {
      font-size: 10px;
      font-weight: 700;
      text-transform: uppercase;
      letter-spacing: 0.04em;
      padding: 3px 8px;
      border-radius: 6px;
      flex-shrink: 0;
    }

    .chip.access { background: rgba(239,139,62,0.2); color: #ef8b3e; }
    .chip.predictive { background: rgba(88,101,242,0.2); color: #8891f5; }
    .chip.analytics { background: rgba(34,197,148,0.2); color: #34d399; }

    .message {
      display: flex;
      align-items: flex-end;
      gap: 9px;
      animation: rise 0.28s cubic-bezier(0.2, 0.8, 0.2, 1);
    }

    @keyframes rise {
      from { opacity: 0; transform: translateY(10px) scale(0.98); }
      to { opacity: 1; transform: translateY(0) scale(1); }
    }

    .message.user { justify-content: flex-end; }
    .message.bot { justify-content: flex-start; }

    .avatar {
      width: 30px;
      height: 30px;
      border-radius: 50%;
      display: flex;
      align-items: center;
      justify-content: center;
      flex-shrink: 0;
      font-weight: 700;
    }

    .bot-avatar {
      padding: 3px;
      background: #fff;
      box-shadow: 0 3px 12px rgba(25,72,96,.17);
      overflow: hidden;
    }

    .bot-avatar img { width: 100%; height: 100%; object-fit: contain; }

    .bot-avatar.pulse {
      animation: avatarPulse 1.6s ease-in-out infinite;
    }

    @keyframes avatarPulse {
      0%, 100% { box-shadow: 0 0 0 0 rgba(239,139,62,0.4); }
      50% { box-shadow: 0 0 0 6px rgba(239,139,62,0); }
    }

    .user-avatar {
      background: rgba(255,255,255,0.1);
      color: #c4cbef;
    }
    .user-avatar svg { width: 18px; height: 18px; }

    .bubble {
      position: relative;
      max-width: 76%;
      padding: 14px 17px;
      border-radius: 18px;
      font-size: 14px;
      line-height: 1.6;
    }

    .message.user .bubble {
      background: linear-gradient(135deg, #ef8b3e, #e67c2c);
      color: #1a2456;
      border-bottom-right-radius: 5px;
      font-weight: 500;
      box-shadow: 0 4px 14px rgba(239,139,62,0.25);
    }

    .message.bot .bubble {
      background: rgba(255,255,255,0.045);
      backdrop-filter: blur(8px);
      color: #e4e8ff;
      border: 1px solid rgba(255,255,255,0.08);
      border-bottom-left-radius: 5px;
    }

    .bubble.error {
      background: rgba(74,31,42,0.6);
      color: #ffb4c0;
      border: 1px solid #7a2e3d;
    }

    .lang-tag {
      display: inline-block;
      font-size: 9.5px;
      font-weight: 700;
      letter-spacing: 0.05em;
      color: #7d87c2;
      background: rgba(255,255,255,0.06);
      padding: 2px 7px;
      border-radius: 5px;
      margin-bottom: 8px;
    }

    .bubble p {
      margin: 0;
      white-space: pre-wrap;
    }

    .attached-image {
      display: block;
      max-width: 100%;
      max-height: 260px;
      border-radius: 12px;
      margin-bottom: 8px;
      object-fit: cover;
    }

    .guide-images {
      margin-top: 13px;
      padding-top: 11px;
      border-top: 1px solid rgba(255,255,255,0.08);
      display: flex;
      flex-direction: column;
      gap: 8px;
    }

    .guide-images-label {
      font-size: 10.5px;
      text-transform: uppercase;
      letter-spacing: 0.07em;
      color: #7d87c2;
      font-weight: 700;
      display: flex;
      align-items: center;
      gap: 5px;
    }

    .guide-images-grid {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
    }

    .guide-image {
      position: relative;
      display: block;
      width: 96px;
      height: 96px;
      border-radius: 10px;
      overflow: hidden;
      border: 1px solid rgba(255,255,255,0.1);
      background: rgba(255,255,255,0.04);
      transition: all 0.18s cubic-bezier(0.4, 0, 0.2, 1);
    }

    .guide-image:hover {
      border-color: rgba(239,139,62,0.5);
      transform: translateY(-2px);
    }

    .guide-image img {
      width: 100%;
      height: 100%;
      object-fit: cover;
      display: block;
    }

    .guide-image-page {
      position: absolute;
      bottom: 0;
      right: 0;
      background: rgba(12,17,48,0.75);
      color: #fff;
      font-size: 9.5px;
      font-weight: 700;
      padding: 2px 6px;
      border-top-left-radius: 8px;
    }

    .guide-image-explain {
      display: block;
      font-size: 11px;
      opacity: 0.75;
      margin-top: 4px;
      line-height: 1.3;
    }

    .thinking-bubble {
      padding: 13px 17px;
    }

    .thinking-step {
      display: flex;
      align-items: center;
      gap: 10px;
    }

    .thinking-dots {
      display: flex;
      gap: 3px;
    }

    .thinking-dots span {
      width: 6px;
      height: 6px;
      border-radius: 50%;
      background: #ef8b3e;
      animation: bounce 1s infinite ease-in-out;
    }

    .thinking-dots span:nth-child(2) { animation-delay: 0.15s; }
    .thinking-dots span:nth-child(3) { animation-delay: 0.3s; }

    @keyframes bounce {
      0%, 60%, 100% { transform: translateY(0); opacity: 0.4; }
      30% { transform: translateY(-4px); opacity: 1; }
    }

    .thinking-text {
      font-size: 13px;
      color: #9aa4d4;
      transition: opacity 0.2s ease;
    }

    .sources {
      margin-top: 13px;
      padding-top: 11px;
      border-top: 1px solid rgba(255,255,255,0.08);
      display: flex;
      flex-direction: column;
      gap: 7px;
    }

    .sources-label {
      font-size: 10.5px;
      text-transform: uppercase;
      letter-spacing: 0.07em;
      color: #7d87c2;
      font-weight: 700;
      display: flex;
      align-items: center;
      gap: 5px;
      margin-bottom: 2px;
    }

    .source {
      display: flex;
      flex-direction: column;
      gap: 3px;
      background: rgba(255,255,255,0.035);
      border: 1px solid rgba(255,255,255,0.06);
      border-radius: 10px;
      padding: 9px 11px;
      transition: all 0.18s cubic-bezier(0.4, 0, 0.2, 1);
    }

    .source.clickable {
      cursor: pointer;
    }

    .source.clickable:hover {
      background: rgba(239,139,62,0.12);
      border-color: rgba(239,139,62,0.4);
      transform: translateX(2px);
    }

    .source.locked {
      opacity: 0.6;
    }

    .source-head {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 8px;
    }

    .source-doc {
      font-size: 12px;
      font-weight: 700;
      color: #ef8b3e;
    }

    .source-icon {
      font-size: 11px;
      color: #7d87c2;
      flex-shrink: 0;
    }

    .source-excerpt {
      font-size: 11.5px;
      color: #9aa4d4;
      line-height: 1.45;
    }

    .source-score {
      display: block;
      font-size: 11px;
      opacity: 0.6;
      margin-top: 4px;
    }

    .composer {
      position: relative;
      z-index: 1;
      display: flex;
      gap: 10px;
      padding: 18px 28px 24px;
      background: rgba(12, 17, 48, 0.7);
      backdrop-filter: blur(14px);
      border-top: 1px solid rgba(255,255,255,0.06);
    }

    .hidden-file-input {
      display: none;
    }

    .attach-btn {
      flex-shrink: 0;
      width: 48px;
      background: rgba(255,255,255,0.06);
      border: 1px solid rgba(255,255,255,0.1);
      border-radius: 13px;
      font-size: 17px;
      cursor: pointer;
      transition: all 0.2s ease;
    }

    .attach-btn:not(:disabled):hover {
      background: rgba(239,139,62,0.15);
      border-color: rgba(239,139,62,0.4);
    }

    .attach-btn:disabled {
      opacity: 0.4;
      cursor: not-allowed;
    }

    .mic-btn {
      flex-shrink: 0;
      width: 48px;
      background: rgba(255,255,255,0.06);
      border: 1px solid rgba(255,255,255,0.1);
      border-radius: 13px;
      font-size: 17px;
      cursor: pointer;
      transition: all 0.2s ease;
    }

    .mic-btn:not(:disabled):hover {
      background: rgba(239,139,62,0.15);
      border-color: rgba(239,139,62,0.4);
    }

    .mic-btn:disabled {
      opacity: 0.4;
      cursor: not-allowed;
    }

    .mic-btn.recording {
      background: rgba(239,139,62,0.25);
      border-color: rgba(239,139,62,0.5);
      animation: pulse-mic 1s infinite;
    }

    @keyframes pulse-mic {
      0%, 100% { opacity: 1; }
      50% { opacity: 0.5; }
    }

    .mic-error {
      align-self: center;
      font-size: 11px;
      color: #ef8b3e;
      max-width: 120px;
    }

    .attach-preview {
      position: relative;
      z-index: 1;
      display: flex;
      align-items: center;
      gap: 10px;
      margin: 0 28px;
      padding: 8px 12px;
      background: rgba(255,255,255,0.06);
      border: 1px solid rgba(255,255,255,0.1);
      border-radius: 12px;
    }

    .attach-preview img {
      width: 40px;
      height: 40px;
      border-radius: 8px;
      object-fit: cover;
      flex-shrink: 0;
    }

    .attach-preview-name {
      flex: 1;
      font-size: 12.5px;
      color: #c4cbef;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .attach-remove {
      flex-shrink: 0;
      width: 24px;
      height: 24px;
      border-radius: 50%;
      border: none;
      background: rgba(255,255,255,0.1);
      color: #fff;
      font-size: 11px;
      cursor: pointer;
      transition: all 0.2s ease;
    }

    .attach-remove:not(:disabled):hover {
      background: rgba(239,68,68,0.4);
    }

    .attach-remove:disabled {
      opacity: 0.4;
      cursor: not-allowed;
    }

    .composer input {
      flex: 1;
      background: rgba(255,255,255,0.06);
      border: 1px solid rgba(255,255,255,0.1);
      border-radius: 13px;
      padding: 14px 17px;
      color: #fff;
      font-size: 14px;
      outline: none;
      transition: all 0.2s ease;
    }

    .composer input:focus {
      border-color: #ef8b3e;
      box-shadow: 0 0 0 3px rgba(239,139,62,0.15);
      background: rgba(255,255,255,0.08);
    }

    .composer input::placeholder {
      color: #6b76b0;
    }

    .composer button {
      background: linear-gradient(135deg, #ef8b3e, #f2a663);
      color: #1a2456;
      border: none;
      border-radius: 13px;
      padding: 0 24px;
      font-size: 14px;
      font-weight: 700;
      cursor: pointer;
      transition: all 0.2s ease;
      min-width: 90px;
      display: flex;
      align-items: center;
      justify-content: center;
    }

    .composer button:not(:disabled):hover {
      transform: translateY(-1px);
      box-shadow: 0 5px 16px rgba(239,139,62,0.35);
    }

    .composer button:disabled {
      opacity: 0.4;
      cursor: not-allowed;
    }

    .send-spinner {
      width: 15px;
      height: 15px;
      border: 2px solid rgba(26,36,86,0.3);
      border-top-color: #1a2456;
      border-radius: 50%;
      animation: spin 0.7s linear infinite;
    }

    @keyframes spin {
      to { transform: rotate(360deg); }
    }

    .chat::-webkit-scrollbar { width: 8px; }
    .chat::-webkit-scrollbar-thumb {
      background: rgba(255,255,255,0.12);
      border-radius: 4px;
    }

    /* SIRYOS visual refresh */
    .page {
\      background:
        radial-gradient(circle at 50% -20%, rgba(9, 113, 151, 0.20), transparent 42%),
        linear-gradient(160deg, #07121d 0%, #0a1725 48%, #081522 100%);
      border-inline: 1px solid rgba(255,255,255,0.04);
    }

    .bg-glow-1 { background: #f2a149; opacity: .18; }
    .bg-glow-2 { background: #08759b; opacity: .25; }

    .header {
      padding: 22px 30px 20px;
      gap: 20px;
      background: rgba(7, 18, 29, 0.76);
      border-bottom-color: rgba(255,255,255,0.07);
      animation: headerIn .55s cubic-bezier(.22,1,.36,1) both;
    }

    @keyframes headerIn {
      from { opacity: 0; transform: translateY(-14px); }
      to { opacity: 1; transform: translateY(0); }
    }

    .brand { min-width: 0; }

    .logo-shell {
      width: 58px;
      height: 58px;
      padding: 5px;
      border-radius: 16px;
      background: rgba(255,255,255,0.96);
      box-shadow: 0 8px 28px rgba(0,0,0,.25);
      display: flex;
      align-items: center;
      justify-content: center;
      flex: 0 0 auto;
    }

    .brand-logo {
      display: block;
      width: 100%;
      height: 100%;
      object-fit: contain;
    }

    .eyebrow, .welcome-kicker {
      color: #f2a149;
      font-size: 10px;
      font-weight: 800;
      letter-spacing: .13em;
      text-transform: uppercase;
    }

    .brand h1 { font-size: 19px; margin-top: 2px; }
    .brand p { color: #8295a9; }

    .status-pill {
      margin-left: auto;
      display: inline-flex;
      align-items: center;
      gap: 7px;
      padding: 7px 10px;
      border: 1px solid rgba(58,211,153,.18);
      border-radius: 999px;
      color: #9be9c9;
      background: rgba(58,211,153,.07);
      font-size: 10px;
      font-weight: 700;
    }

    .status-pill > span {
      width: 6px;
      height: 6px;
      border-radius: 50%;
      background: #3ad399;
      box-shadow: 0 0 0 4px rgba(58,211,153,.10);
      animation: statusPulse 2s ease-in-out infinite;
    }

    @keyframes statusPulse { 50% { box-shadow: 0 0 0 7px rgba(58,211,153,0); } }

    .mode-section { display: flex; flex-direction: column; gap: 9px; }

    .mode-heading {
      display: flex;
      justify-content: space-between;
      color: #cbd7e3;
      font-size: 11px;
      font-weight: 700;
    }

    .mode-hint { color: #64788d; font-weight: 500; }

    .level-picker {
      width: 100%;
      gap: 8px;
      padding: 0;
      background: transparent;
    }

    .level-option {
      position: relative;
      flex: 1 1 0;
      min-width: 0;
      animation: optionIn .5s cubic-bezier(.22,1,.36,1) both;
    }

    .level-option:nth-child(2) { animation-delay: .07s; }
    .level-option:nth-child(3) { animation-delay: .14s; }
    @keyframes optionIn { from { opacity: 0; transform: translateY(8px); } }

    .level-picker .level-option > button:first-child {
      width: 100%;
      min-height: 58px;
      padding: 9px 40px 9px 12px;
      border: 1px solid rgba(255,255,255,.08);
      border-radius: 13px;
      background: rgba(255,255,255,.035);
      display: flex;
      align-items: center;
      gap: 10px;
      text-align: left;
    }

    .level-picker .level-option > button:first-child:hover {
      border-color: rgba(242,161,73,.33);
      background: rgba(255,255,255,.055);
      transform: translateY(-2px);
    }

    .level-picker .level-option > button:first-child.active {
      color: #fff;
      border-color: rgba(242,161,73,.52);
      background: linear-gradient(135deg, rgba(242,161,73,.18), rgba(8,117,155,.15));
      box-shadow: inset 0 0 0 1px rgba(242,161,73,.08), 0 8px 24px rgba(0,0,0,.16);
    }

    .level-icon {
      width: 30px;
      height: 30px;
      display: grid;
      place-items: center;
      border-radius: 9px;
      color: #f2a149;
      background: rgba(242,161,73,.10);
      font-size: 15px;
      flex: 0 0 auto;
    }

    .level-copy { display: flex; flex-direction: column; gap: 3px; min-width: 0; }
    .level-title { font-size: 12.5px; font-weight: 800; color: #edf4fa; }
    .level-tier { font-size: 9.5px; font-weight: 600; color: #7f93a7; }

    .info-button {
      position: absolute;
      z-index: 3;
      top: 50%;
      right: 11px;
      transform: translateY(-50%);
      width: 23px;
      height: 23px;
      padding: 0;
      border: 1px solid rgba(255,255,255,.14);
      border-radius: 50%;
      background: rgba(255,255,255,.04);
      color: #8da0b2;
      font: 800 12px/1 Georgia, serif;
      cursor: help;
    }

    .info-button:hover, .info-button:focus-visible {
      color: #07121d;
      border-color: #f2a149;
      background: #f2a149;
      outline: none;
    }

    .mode-tooltip {
      position: absolute;
      z-index: 20;
      top: calc(100% + 12px);
      right: -7px;
      width: 235px;
      padding: 13px 14px;
      border: 1px solid rgba(242,161,73,.25);
      border-radius: 12px;
      background: #102234;
      box-shadow: 0 14px 38px rgba(0,0,0,.38);
      color: #dbe6ef;
      text-align: left;
      font-family: 'Inter', system-ui, sans-serif;
      opacity: 0;
      visibility: hidden;
      pointer-events: none;
      transform: translateY(-5px) scale(.98);
      transform-origin: top right;
      transition: .18s ease;
    }

    .mode-tooltip::before {
      content: '';
      position: absolute;
      top: -5px;
      right: 14px;
      width: 9px;
      height: 9px;
      background: #102234;
      border-left: 1px solid rgba(242,161,73,.25);
      border-top: 1px solid rgba(242,161,73,.25);
      transform: rotate(45deg);
    }

    .info-button:hover .mode-tooltip,
    .info-button:focus-visible .mode-tooltip {
      opacity: 1;
      visibility: visible;
      transform: translateY(0) scale(1);
    }

    .mode-tooltip strong { display: block; color: #fff; font-size: 12px; margin-bottom: 5px; }
    .mode-tooltip > span { display: block; color: #aebdcc; font-size: 11px; line-height: 1.45; }
    .mode-tooltip em {
      display: inline-block;
      margin-top: 9px;
      padding: 4px 7px;
      border-radius: 6px;
      background: rgba(242,161,73,.10);
      color: #f4b875;
      font-size: 9.5px;
      font-style: normal;
      font-weight: 800;
    }

    .chat { padding: 28px 30px; }
    .empty { max-width: 530px; animation: emptyIn .65s .12s cubic-bezier(.22,1,.36,1) both; }
    @keyframes emptyIn { from { opacity: 0; transform: translateY(12px); } }
    .empty-badge { border-radius: 50%; box-shadow: 0 0 35px rgba(242,161,73,.12); }
    .empty h2 { margin-top: 7px; font-size: 22px; }
    .empty p { color: #8da0b2; }
    .suggestions { display: grid; grid-template-columns: repeat(3, 1fr); }
    .suggestions button { flex-direction: column; align-items: flex-start; min-height: 78px; }
    .suggestions button:hover { transform: translateY(-3px); }
    .composer { background: rgba(7,18,29,.82); }

    /* Light SIRYOS theme */
    .page {
      background:
        radial-gradient(circle at 6% 8%, rgba(242,161,73,.16), transparent 30%),
        radial-gradient(circle at 94% 28%, rgba(8,117,155,.13), transparent 34%),
        linear-gradient(155deg, #fffdf9 0%, #f4f9fc 52%, #edf6fa 100%);
      border-inline-color: rgba(8,117,155,.08);
      box-shadow: 0 0 80px rgba(25,72,96,.13);
    }

    .bg-glow { filter: blur(105px); opacity: .13; }
    .bg-glow-1 { background: #f2a149; }
    .bg-glow-2 { background: #08759b; opacity: .12; }

    .header {
      background: rgba(255,255,255,.76);
      border-bottom-color: rgba(18,92,123,.10);
      box-shadow: 0 8px 30px rgba(38,93,118,.06);
    }

    .logo-shell {
      background: #fff;
      border: 1px solid rgba(8,117,155,.08);
      box-shadow: 0 8px 24px rgba(25,72,96,.10);
    }

    .brand h1, .empty h2 { color: #12364b; }
    .brand p { color: #667f8e; }
    .mode-heading { color: #34596c; }
    .mode-hint { color: #8ba0ac; }

    .status-pill {
      color: #15815c;
      background: rgba(43,177,126,.08);
      border-color: rgba(43,177,126,.18);
    }

    .level-picker .level-option > button:first-child {
      background: rgba(255,255,255,.72);
      border-color: rgba(18,92,123,.12);
      box-shadow: 0 5px 18px rgba(32,78,100,.05);
    }

    .level-picker .level-option > button:first-child:hover {
      background: #fff;
      border-color: rgba(242,161,73,.55);
      box-shadow: 0 9px 24px rgba(32,78,100,.09);
    }

    .level-picker .level-option > button:first-child.active {
      color: #12364b;
      background: linear-gradient(135deg, #fff7eb, #eef9fc);
      border-color: #e9a252;
      box-shadow: inset 0 0 0 1px rgba(242,161,73,.10), 0 8px 24px rgba(32,78,100,.09);
    }

    .level-title { color: #183f54; }
    .level-tier { color: #718895; }
    .level-icon { background: #fff1de; color: #e58b2e; }

    .info-button {
      color: #66818f;
      background: rgba(8,117,155,.05);
      border-color: rgba(8,117,155,.16);
    }

    .mode-tooltip {
      color: #34596c;
      background: #fff;
      border-color: rgba(242,161,73,.45);
      box-shadow: 0 15px 38px rgba(36,78,98,.18);
    }

    .mode-tooltip::before { background: #fff; border-color: rgba(242,161,73,.45); }
    .mode-tooltip strong { color: #12364b; }
    .mode-tooltip > span { color: #617987; }
    .mode-tooltip em { background: #fff1de; color: #b8661d; }

    .empty { color: #708894; }
    .empty-badge {
      background: linear-gradient(135deg, #fff1de, #e5f4f9);
      border-color: rgba(242,161,73,.35);
      box-shadow: 0 10px 34px rgba(35,93,117,.10);
    }
    .empty p { color: #667f8e; }

    .suggestions button {
      color: #34596c;
      background: rgba(255,255,255,.72);
      border-color: rgba(18,92,123,.12);
      box-shadow: 0 6px 18px rgba(32,78,100,.05);
    }
    .suggestions button:hover {
      color: #12364b;
      background: #fff;
      border-color: rgba(242,161,73,.48);
      box-shadow: 0 11px 26px rgba(32,78,100,.10);
    }

    .message.bot .bubble {
      color: #234b60;
      background: rgba(255,255,255,.80);
      border-color: rgba(18,92,123,.11);
      box-shadow: 0 7px 22px rgba(32,78,100,.06);
    }
    .message.user .bubble { color: #fff; background: linear-gradient(135deg, #08759b, #0b8bad); }
    .user-avatar { background: #dcebf1; color: #406476; }
    .lang-tag, .thinking-text, .sources-label, .guide-images-label { color: #758d9a; }
    .sources, .guide-images { border-top-color: rgba(18,92,123,.10); }
    .source { background: #f4f9fb; border-color: rgba(18,92,123,.10); }
    .source-excerpt { color: #637c89; }

    .composer {
      background: rgba(255,255,255,.80);
      border-top-color: rgba(18,92,123,.10);
      box-shadow: 0 -8px 30px rgba(32,78,100,.05);
    }
    .composer input {
      color: #173f54;
      background: #f5f9fb;
      border-color: rgba(18,92,123,.13);
    }
    .composer input:focus { background: #fff; }
    .composer input::placeholder { color: #91a4ae; }
    .mic-btn { background: #f1f7f9; border-color: rgba(18,92,123,.12); }

    .chat::-webkit-scrollbar-thumb { background: rgba(8,117,155,.18); }

    .siryos-cursor,
    .siryos-cursor-halo {
      position: fixed;
      z-index: 1000;
      top: 0;
      left: 0;
      pointer-events: none;
      opacity: 0;
      will-change: transform;
      transition: width .2s ease, height .2s ease, opacity .18s ease, background .2s ease, box-shadow .2s ease;
    }

    .siryos-cursor {
      width: 34px;
      height: 34px;
      padding: 5px;
      border-radius: 50%;
      background: rgba(255,255,255,.92);
      border: 1px solid rgba(242,161,73,.45);
      box-shadow: 0 7px 22px rgba(25,72,96,.18);
    }

    .siryos-cursor img {
      width: 100%;
      height: 100%;
      display: block;
      object-fit: contain;
      mix-blend-mode: multiply;
    }

    .siryos-cursor-halo {
      z-index: 999;
      width: 68px;
      height: 68px;
      margin: -17px 0 0 -17px;
      border-radius: 50%;
      border: 1px solid rgba(8,117,155,.18);
      background: radial-gradient(circle, rgba(242,161,73,.09), transparent 70%);
    }

    .siryos-cursor.visible,
    .siryos-cursor-halo.visible { opacity: 1; }
    .siryos-cursor.interacting {
      width: 43px;
      height: 43px;
      background: #fff7ed;
      border-color: #f2a149;
      box-shadow: 0 8px 28px rgba(242,161,73,.28);
    }
    .siryos-cursor-halo.interacting {
      width: 88px;
      height: 88px;
      margin: -27px 0 0 -27px;
      border-color: rgba(242,161,73,.28);
      background: radial-gradient(circle, rgba(242,161,73,.14), transparent 68%);
    }
    .siryos-cursor.thinking { animation: sThink 1.1s ease-in-out infinite; }
    @keyframes sThink { 50% { rotate: 8deg; scale: 1.12; box-shadow: 0 0 0 9px rgba(242,161,73,0); } }

    .smart-toolbar {
      min-height: 35px;
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 12px;
      padding-top: 2px;
    }

    .active-mode-indicator {
      display: inline-flex;
      align-items: center;
      gap: 7px;
      color: #637e8d;
      font-size: 10px;
      font-weight: 700;
    }

    .indicator-dot {
      width: 7px;
      height: 7px;
      border-radius: 50%;
      background: #f2a149;
      box-shadow: 0 0 0 4px rgba(242,161,73,.12);
    }

    .toolbar-actions { display: flex; align-items: center; gap: 6px; }
    .toolbar-actions button {
      min-height: 30px;
      padding: 5px 9px;
      border: 1px solid rgba(18,92,123,.11);
      border-radius: 8px;
      background: rgba(255,255,255,.62);
      color: #456778;
      font-size: 9.5px;
      font-weight: 700;
      cursor: pointer;
      transition: .18s ease;
    }
    .toolbar-actions button:hover:not(:disabled) {
      color: #12364b;
      background: #fff;
      border-color: rgba(242,161,73,.42);
      transform: translateY(-1px);
    }
    .toolbar-actions button:disabled { opacity: .38; cursor: not-allowed; }
    .toolbar-actions button > span { color: #e7892e; margin-right: 3px; }
    kbd {
      margin-left: 4px;
      padding: 2px 4px;
      border: 1px solid rgba(18,92,123,.12);
      border-bottom-width: 2px;
      border-radius: 4px;
      background: #f4f8fa;
      color: #78909d;
      font: 700 8px/1 system-ui, sans-serif;
    }

    .composer { padding-bottom: 30px; }
    .composer-status {
      position: absolute;
      right: 31px;
      bottom: 7px;
      display: flex;
      align-items: center;
      gap: 8px;
      color: #90a2ac;
      font-size: 9px;
    }
    .composer-mode {
      padding-right: 8px;
      border-right: 1px solid rgba(18,92,123,.12);
      color: #d77c25;
      font-weight: 800;
    }

    @media (max-width: 720px) {
      .header { padding: 16px; gap: 15px; }
      .logo-shell { width: 44px; height: 44px; border-radius: 13px; }
      .brand h1 { font-size: 16px; }
      .brand p, .eyebrow, .mode-hint { display: none; }
      .status-pill { padding: 6px 8px; font-size: 0; gap: 0; }
      .level-picker { gap: 5px; }
      .level-picker .level-option > button:first-child {
        min-height: 68px;
        padding: 8px 28px 8px 8px;
        flex-direction: column;
        justify-content: center;
        gap: 4px;
        text-align: center;
      }
      .level-icon { width: 24px; height: 24px; font-size: 12px; }
      .level-tier { display: none; }
      .info-button { top: 12px; right: 7px; transform: none; width: 19px; height: 19px; }
      .mode-tooltip { position: fixed; top: 155px; right: 16px; left: 16px; width: auto; transform-origin: top center; }
      .mode-tooltip::before { display: none; }
      .chat { padding: 20px 16px; }
      .suggestions { grid-template-columns: 1fr; }
      .suggestions button { min-height: auto; flex-direction: row; align-items: center; }
      .bubble { max-width: 86%; }
      .composer { padding: 12px 16px 16px; gap: 7px; }
      .composer button { min-width: 50px; padding: 0 14px; }
      .mic-btn { width: 44px; }
      .smart-toolbar { align-items: flex-start; }
      .active-mode-indicator { padding-top: 8px; }
      .toolbar-actions button { width: 32px; padding: 5px; font-size: 0; }
      .toolbar-actions button > span { margin: 0; font-size: 14px; }
      .toolbar-actions kbd { display: none; }
      .composer-status { right: 18px; }
    }

    @media (hover: none), (pointer: coarse) {
      .siryos-cursor, .siryos-cursor-halo { display: none; }
    }

    @media (prefers-reduced-motion: reduce) {
      *, *::before, *::after { animation-duration: .01ms !important; animation-iteration-count: 1 !important; scroll-behavior: auto !important; }
    }
  `]
})
export class ChatComponent implements OnInit, AfterViewChecked, OnDestroy {
  @ViewChild('scrollAnchor') private scrollAnchor!: ElementRef<HTMLDivElement>;
  @ViewChild('questionInput') private questionInput!: ElementRef<HTMLInputElement>;

  readonly apiOrigin = 'http://localhost:8081';
  private readonly apiUrl = `${this.apiOrigin}/api/chat`;
  private readonly imageApiUrl = `${this.apiOrigin}/api/chat/image`;
  private readonly sessionStorageKey = 'behave_chat_session_id';
  private readonly maxImageBytes = 15 * 1024 * 1024; // aligné sur ChatService.MAX_IMAGE_UPLOAD_BYTES (backend)

  readonly levels: { value: ExplanationLevel; label: string; icon: string; tier: string; tierLong: string; description: string }[] = [
    {
      value: 'SIMPLE',
      label: 'Simple',
      icon: '⚡',
      tier: 'Essentiel',
      tierLong: 'Version gratuite',
      description: 'Une réponse courte, claire et directe pour comprendre rapidement l’essentiel.'
    },
    {
      value: 'DETAILLE',
      label: 'Détaillé',
      icon: '✦',
      tier: 'Plus de contexte',
      tierLong: 'Version semi-payante',
      description: 'Une explication approfondie avec davantage de contexte, de références et d’exemples.'
    },
    {
      value: 'TECHNIQUE',
      label: 'Technique',
      icon: '⌘',
      tier: 'Expert',
      tierLong: 'Version payante',
      description: 'Une analyse experte orientée développement, architecture, configuration et aspects purement techniques.'
    }
  ];

  // NOUVEAU (historique des conversations, façon ChatGPT).
  readonly sessions = signal<ChatSessionSummary[]>([]);
  readonly currentSessionId = signal<string | null>(null);
  readonly sidebarOpen = signal(true);
  readonly level = signal<ExplanationLevel>('DETAILLE');
  readonly messages = signal<ChatMessage[]>([]);
  readonly loading = signal(false);
  readonly copied = signal(false);
  readonly cursorVisible = signal(false);
  readonly cursorInteracting = signal(false);
  readonly cursorTransform = signal('translate3d(-100px, -100px, 0) translate(-50%, -50%)');

  private cursorTargetX = -100;
  private cursorTargetY = -100;
  private cursorCurrentX = -100;
  private lastAutoScrollMessageCount = 0;
  private lastAutoScrollLoading = false;
  private cursorCurrentY = -100;
  private cursorAnimationFrame?: number;
  private copyResetTimer?: ReturnType<typeof setTimeout>;

  readonly selectedImage = signal<File | null>(null);
  readonly selectedImagePreviewUrl = signal<string | null>(null);

  // Entrée vocale via l'API Web Speech du navigateur : le texte transcrit
  // suit ensuite EXACTEMENT le même chemin qu'une question tapée au
  // clavier (§3 : "la voix n'est pas une recherche séparée").
  private recognition: any = null;
  readonly speechSupported: boolean =
    typeof window !== 'undefined' && !!((window as any).SpeechRecognition || (window as any).webkitSpeechRecognition);
  readonly isRecording = signal(false);
  readonly micError = signal<string | null>(null);

  // Historique léger (texte seul) envoyé au backend pour le contexte
  // conversationnel (§22).
  private readonly conversationHistory: HistoryTurn[] = [];
  private readonly maxHistoryTurns = 6;

  private readonly textThinkingSteps = [
    'Recherche dans les guides indexés…',
    'Analyse des passages pertinents…',
    'Génération de la réponse…'
  ];
  private readonly imageThinkingSteps = [
    "Lecture de l'image…",
    'Analyse du contenu visuel…',
    "Rédaction de l'explication…"
  ];
  thinkingSteps: string[] = this.textThinkingSteps;
  readonly thinkingStepIndex = signal(0);
  private thinkingInterval?: ReturnType<typeof setInterval>;

  question = '';

  constructor(private http: HttpClient, private chatHistoryService: ChatHistoryService) {}

  ngOnInit(): void {
    // Restaure la session en cours (si l'utilisateur avait déjà discuté)
    // pour que le rafraîchissement de la page ne perde pas la conversation.
    const existingId = sessionStorage.getItem(this.sessionStorageKey);
    if (existingId) {
      this.currentSessionId.set(existingId);
      this.chatHistoryService.getMessages(existingId).subscribe({
        next: (views) => this.messages.set(views.map((v) => this.toChatMessage(v))),
        error: () => {
          // Session inconnue côté serveur (ex: base réinitialisée) -> on
          // repart simplement d'une conversation vide, sans bloquer l'UI.
          sessionStorage.removeItem(this.sessionStorageKey);
          this.currentSessionId.set(null);
        }
      });
    }
    this.refreshSessions();
  }

  ngAfterViewChecked(): void {
  const messageCount = this.messages().length;
  const isLoading = this.loading();
  const hasNewContent = messageCount !== this.lastAutoScrollMessageCount || isLoading !== this.lastAutoScrollLoading;

  this.lastAutoScrollMessageCount = messageCount;
  this.lastAutoScrollLoading = isLoading;

  if (hasNewContent && this.scrollAnchor) {
    this.scrollAnchor.nativeElement.scrollTop = this.scrollAnchor.nativeElement.scrollHeight;
  }
}

  ngOnDestroy(): void {
    this.stopThinkingAnimation();
    this.revokeSelectedImagePreview();
    if (this.cursorAnimationFrame) cancelAnimationFrame(this.cursorAnimationFrame);
    if (this.copyResetTimer) clearTimeout(this.copyResetTimer);
  }

  @HostListener('document:mousemove', ['$event'])
  onPointerMove(event: MouseEvent): void {
    this.cursorTargetX = event.clientX;
    this.cursorTargetY = event.clientY;
    this.cursorVisible.set(true);
    const target = event.target as HTMLElement | null;
    this.cursorInteracting.set(!!target?.closest('button, a, input, .source.clickable'));
    if (!this.cursorAnimationFrame) this.animateSiryosCursor();
  }

  @HostListener('document:mouseleave')
  onPointerLeave(): void {
    this.cursorVisible.set(false);
  }

  @HostListener('document:keydown', ['$event'])
  onKeyboardShortcut(event: KeyboardEvent): void {
    if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') {
      event.preventDefault();
      this.focusComposer();
    }
    if (event.key === 'Escape' && document.activeElement === this.questionInput?.nativeElement) {
      this.question = '';
      this.questionInput.nativeElement.blur();
    }
  }

  private animateSiryosCursor(): void {
    this.cursorCurrentX += (this.cursorTargetX - this.cursorCurrentX) * 0.18;
    this.cursorCurrentY += (this.cursorTargetY - this.cursorCurrentY) * 0.18;
    this.cursorTransform.set(
      `translate3d(${this.cursorCurrentX}px, ${this.cursorCurrentY}px, 0) translate(-50%, -50%)`
    );
    const stillMoving = Math.abs(this.cursorTargetX - this.cursorCurrentX) > 0.1 ||
      Math.abs(this.cursorTargetY - this.cursorCurrentY) > 0.1;
    if (stillMoving) {
      this.cursorAnimationFrame = requestAnimationFrame(() => this.animateSiryosCursor());
    } else {
      this.cursorAnimationFrame = undefined;
    }
  }

  activeLevelLabel(): string {
    return this.levels.find((item) => item.value === this.level())?.label ?? 'Détaillé';
  }

  lastBotAnswer(): string {
    return [...this.messages()].reverse().find((message) => message.role === 'bot' && !message.error)?.text ?? '';
  }

  focusComposer(): void {
    this.questionInput?.nativeElement.focus();
  }

  async copyLastAnswer(): Promise<void> {
    const answer = this.lastBotAnswer();
    if (!answer) return;
    await navigator.clipboard.writeText(answer);
    this.copied.set(true);
    if (this.copyResetTimer) clearTimeout(this.copyResetTimer);
    this.copyResetTimer = setTimeout(() => this.copied.set(false), 1600);
  }

  /**
   * MODIFIÉ (historique des conversations) : crée explicitement une
   * nouvelle session côté serveur (au lieu de se contenter d'oublier
   * l'ancien id) pour qu'elle apparaisse immédiatement dans la sidebar,
   * même avant le premier message échangé.
   */
  newConversation(): void {
    if (this.loading()) return;
    this.messages.set([]);
    this.conversationHistory.splice(0);
    this.question = '';
    this.sidebarOpen.set(false);
    this.chatHistoryService.createSession().subscribe({
      next: (session) => {
        this.currentSessionId.set(session.id);
        sessionStorage.setItem(this.sessionStorageKey, session.id);
        this.refreshSessions();
        this.focusComposer();
      },
      error: () => {
        // Backend indisponible : on continue quand même en local, une
        // session sera (re)créée dès le premier message envoyé.
        this.currentSessionId.set(null);
        sessionStorage.removeItem(this.sessionStorageKey);
        this.focusComposer();
      }
    });
  }

  /** Recharge la liste des sessions affichée dans la sidebar (titre, date de mise à jour). */
  refreshSessions(): void {
    this.chatHistoryService.listSessions().subscribe({
      next: (sessions) => this.sessions.set(sessions),
      error: () => { /* sidebar simplement vide si le backend est indisponible */ }
    });
  }

  /** Ouvre une conversation passée : recharge ses messages et bascule dessus. */
  loadSession(session: ChatSessionSummary): void {
    if (this.loading() || session.id === this.currentSessionId()) {
      this.sidebarOpen.set(false);
      return;
    }
    this.chatHistoryService.getMessages(session.id).subscribe({
      next: (views) => {
        this.messages.set(views.map((v) => this.toChatMessage(v)));
        this.conversationHistory.splice(0);
        views.forEach((v) => this.pushHistory(v.role, v.content));
        this.currentSessionId.set(session.id);
        sessionStorage.setItem(this.sessionStorageKey, session.id);
        this.sidebarOpen.set(false);
      },
      error: () => this.pushErrorMessage("Impossible de charger cette conversation.")
    });
  }

  /** Supprime une conversation de l'historique (icône corbeille dans la sidebar). */
  deleteSession(session: ChatSessionSummary, event: Event): void {
    event.stopPropagation();
    if (!confirm(`Supprimer la conversation "${session.title}" ? Cette action est définitive.`)) {
      return;
    }
    this.chatHistoryService.deleteSession(session.id).subscribe({
      next: () => {
        this.sessions.update((list) => list.filter((s) => s.id !== session.id));
        if (session.id === this.currentSessionId()) {
          this.newConversation();
        }
      },
      error: () => this.pushErrorMessage('Suppression de la conversation impossible.')
    });
  }

  /** Convertit une vue d'historique backend (role user/assistant) en bulle de chat affichée. */
  private toChatMessage(view: ChatMessageView): ChatMessage {
    return { role: view.role === 'assistant' ? 'bot' : 'user', text: view.content };
  }

  askSuggestion(text: string): void {
    this.question = text;
    this.send();
  }

  /** true si le formulaire peut être soumis : au moins un texte ou une image. */
  canSend(): boolean {
    return this.question.trim().length > 0 || this.selectedImage() !== null;
  }

  onImageSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0] ?? null;
    input.value = ''; // permet de resélectionner le même fichier ensuite

    if (!file) {
      return;
    }
    if (!file.type.startsWith('image/')) {
      this.pushErrorMessage("Le fichier sélectionné n'est pas une image.");
      return;
    }
    if (file.size > this.maxImageBytes) {
      this.pushErrorMessage('Image trop volumineuse (15 Mo max).');
      return;
    }

    this.revokeSelectedImagePreview();
    this.selectedImage.set(file);
    this.selectedImagePreviewUrl.set(URL.createObjectURL(file));
  }

  removeSelectedImage(): void {
    this.revokeSelectedImagePreview();
    this.selectedImage.set(null);
  }

  private revokeSelectedImagePreview(): void {
    const url = this.selectedImagePreviewUrl();
    if (url) {
      URL.revokeObjectURL(url);
    }
    this.selectedImagePreviewUrl.set(null);
  }

  private pushErrorMessage(text: string): void {
    this.messages.update((msgs) => [...msgs, { role: 'bot', text, error: true }]);
  }

  private getOrCreateSessionId(): string {
    let id = sessionStorage.getItem(this.sessionStorageKey);
    if (!id) {
      id = crypto.randomUUID();
      sessionStorage.setItem(this.sessionStorageKey, id);
    }
    return id;
  }

  private pushHistory(role: 'user' | 'assistant', text: string): void {
    if (!text) return;
    this.conversationHistory.push({ role, text });
    if (this.conversationHistory.length > this.maxHistoryTurns) {
      this.conversationHistory.shift();
    }
  }

  sourcesLabel(language?: Language): string {
    return language === 'AR' ? 'المصادر' : 'Sources';
  }

  guideImagesLabel(language?: Language): string {
    if (language === 'AR') return 'صور من الدليل';
    if (language === 'EN') return 'From the guide';
    return 'Extrait du guide';
  }

  openSource(src: SourceReference): void {
    if (!src.viewablePage) return;
    window.open(`${this.apiOrigin}${src.sourceUrl}`, '_blank');
  }

  /**
   * Libellé du bouton micro. Volontairement une méthode plutôt qu'un
   * ternaire inline dans le template : les apostrophes françaises
   * ("l'enregistrement") ne peuvent pas être échappées de façon fiable
   * à l'intérieur d'un template literal TypeScript imbriqué dans une
   * expression Angular — le backslash est consommé par le parseur JS
   * avant qu'Angular ne voie la chaîne, ce qui casse le parsing du
   * template. Une méthode évite complètement le problème.
   */
  micTitle(): string {
    return this.isRecording() ? "Arrêter l'enregistrement" : "Poser la question à l'oral";
  }

  toggleRecording(): void {
    if (!this.speechSupported) {
      this.micError.set("La reconnaissance vocale n'est pas supportée par ce navigateur.");
      return;
    }
    if (this.isRecording()) {
      this.recognition?.stop();
      return;
    }

    const SpeechRecognitionCtor = (window as any).SpeechRecognition || (window as any).webkitSpeechRecognition;
    this.recognition = new SpeechRecognitionCtor();
    this.recognition.lang = 'fr-FR'; // ajustable selon la langue de session si besoin
    this.recognition.interimResults = false;
    this.recognition.maxAlternatives = 1;

    this.recognition.onstart = () => {
      this.micError.set(null);
      this.isRecording.set(true);
    };

    this.recognition.onerror = (event: any) => {
      this.isRecording.set(false);
      const message = event?.error === 'not-allowed'
        ? 'Permission micro refusée.'
        : 'Erreur de reconnaissance vocale.';
      this.micError.set(message);
    };

    this.recognition.onend = () => {
      this.isRecording.set(false);
    };

    // La transcription est déposée dans le champ texte (§3 : possibilité
    // de corriger avant l'envoi) — on n'envoie PAS automatiquement,
    // l'utilisateur valide avec le bouton Envoyer comme pour une
    // question tapée.
    this.recognition.onresult = (event: any) => {
      const transcript = event.results?.[0]?.[0]?.transcript ?? '';
      this.question = transcript;
    };

    this.recognition.start();
  }

  private startThinkingAnimation(mode: 'text' | 'image'): void {
    this.thinkingSteps = mode === 'image' ? this.imageThinkingSteps : this.textThinkingSteps;
    this.thinkingStepIndex.set(0);
    this.thinkingInterval = setInterval(() => {
      this.thinkingStepIndex.update((i) => (i + 1) % this.thinkingSteps.length);
    }, 900);
  }

  private stopThinkingAnimation(): void {
    if (this.thinkingInterval) {
      clearInterval(this.thinkingInterval);
      this.thinkingInterval = undefined;
    }
  }

  send(): void {
    if (this.loading() || !this.canSend()) {
      return;
    }

    const image = this.selectedImage();
    if (image) {
      this.sendImage(image);
    } else {
      this.sendText();
    }
  }

  private sendText(): void {
    const text = this.question.trim();
    if (!text) {
      return;
    }

    this.messages.update((msgs) => [...msgs, { role: 'user', text }]);
    this.question = '';
    this.loading.set(true);
    this.startThinkingAnimation('text');

    this.http.post<ChatResponse>(this.apiUrl, {
      question: text,
      level: this.level(),
      sessionId: this.getOrCreateSessionId(),
      history: this.conversationHistory
    }).subscribe({
      next: (res) => {
        this.stopThinkingAnimation();
        this.messages.update((msgs) => [...msgs, {
          role: 'bot',
          text: res.answer,
          sources: res.sources,
          images: res.images,
          language: res.language
        }]);
        this.pushHistory('user', text);
        this.pushHistory('assistant', res.answer);
        this.rememberSession(res.sessionId);
        this.loading.set(false);
      },
      error: (err) => {
        this.stopThinkingAnimation();
        this.messages.update((msgs) => [...msgs, {
          role: 'bot',
          text: `Erreur de connexion au backend (${err.status || 'réseau'}). Vérifie que l'application Spring Boot tourne bien sur ${this.apiUrl}.`,
          error: true
        }]);
        this.loading.set(false);
      }
    });
  }

  /** Envoie une image (+ question optionnelle) à POST /api/chat/image. */
  private sendImage(image: File): void {
    const text = this.question.trim();
    // On garde l'aperçu (déjà créé lors de la sélection) pour l'afficher
    // dans la bulle utilisateur, sans le révoquer tant que le message existe.
    const previewUrl = this.selectedImagePreviewUrl() ?? undefined;

    this.messages.update((msgs) => [...msgs, {
      role: 'user',
      text,
      attachedImagePreviewUrl: previewUrl
    }]);

    this.question = '';
    this.selectedImage.set(null);
    this.selectedImagePreviewUrl.set(null); // ne plus être "sélectionnée" (mais l'URL reste valable, affichée par le message ci-dessus)

    this.loading.set(true);
    this.startThinkingAnimation('image');

    const formData = new FormData();
    formData.append('image', image, image.name);
    if (text) {
      formData.append('question', text);
    }
    formData.append('level', this.level());
    formData.append('sessionId', this.getOrCreateSessionId());
    formData.append('history', JSON.stringify(this.conversationHistory));

    this.http.post<ChatResponse>(this.imageApiUrl, formData).subscribe({
      next: (res) => {
        this.stopThinkingAnimation();
        this.messages.update((msgs) => [...msgs, {
          role: 'bot',
          text: res.answer,
          sources: res.sources,
          images: res.images,
          language: res.language
        }]);
        this.pushHistory('user', text || 'Image envoyée');
        this.pushHistory('assistant', res.answer);
        this.rememberSession(res.sessionId);
        this.loading.set(false);
      },
      error: (err) => {
        this.stopThinkingAnimation();
        this.messages.update((msgs) => [...msgs, {
          role: 'bot',
          text: `Erreur lors de l'analyse de l'image (${err.status || 'réseau'}). Vérifie que l'application Spring Boot tourne bien sur ${this.apiOrigin}.`,
          error: true
        }]);
        this.loading.set(false);
      }
    });
  }

  /**
   * NOUVEAU (historique) : le backend résout/crée toujours la session
   * réelle et la renvoie dans `ChatResponse.sessionId` (cf.
   * ChatController) — c'est cette valeur qu'il faut mémoriser, pas celle
   * qu'on avait éventuellement envoyée, pour rester dans la même
   * conversation à l'échange suivant. On rafraîchit aussi la sidebar pour
   * que le titre auto-généré (1er message) et la date apparaissent tout
   * de suite.
   */
  private rememberSession(sessionId: string | undefined | null): void {
    if (!sessionId) return;
    sessionStorage.setItem(this.sessionStorageKey, sessionId);
    this.currentSessionId.set(sessionId);
    this.refreshSessions();
  }
}
