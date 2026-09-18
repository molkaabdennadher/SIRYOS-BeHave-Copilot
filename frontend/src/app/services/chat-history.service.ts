import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { API_ORIGIN } from '../shared/api';
import { ChatMessageView, ChatSessionSummary } from '../shared/models';

/**
 * NOUVEAU (historique des conversations) — miroir front de
 * ChatHistoryController. Alimente la sidebar façon ChatGPT : liste des
 * sessions, ouverture d'une session pour revoir ce qui a été écrit,
 * suppression, création explicite d'une nouvelle conversation.
 */
@Injectable({ providedIn: 'root' })
export class ChatHistoryService {
  private readonly baseUrl = `${API_ORIGIN}/api/chat/sessions`;

  constructor(private http: HttpClient) {}

  listSessions(): Observable<ChatSessionSummary[]> {
    return this.http.get<ChatSessionSummary[]>(this.baseUrl);
  }

  createSession(): Observable<ChatSessionSummary> {
    return this.http.post<ChatSessionSummary>(this.baseUrl, null);
  }

  getMessages(sessionId: string): Observable<ChatMessageView[]> {
    return this.http.get<ChatMessageView[]>(`${this.baseUrl}/${sessionId}/messages`);
  }

  deleteSession(sessionId: string): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${sessionId}`);
  }
}
