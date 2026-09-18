import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { API_ORIGIN } from '../shared/api';
import { GuideSummary } from '../shared/models';

/**
 * NOUVEAU (espace admin) — miroir front de GuideAdminController.
 *
 * La règle métier ("si le guide n'a pas changé, on ne refait ni le
 * chunking ni l'embedding") vit entièrement côté backend (hash SHA-256,
 * cf. GuideAdminService) : ce service se contente d'exposer les 4 actions
 * (upload, liste, suppression, réindexation forcée) sans dupliquer de
 * logique métier côté client.
 */
@Injectable({ providedIn: 'root' })
export class GuideAdminService {
  private readonly baseUrl = `${API_ORIGIN}/api/admin/guides`;

  constructor(private http: HttpClient) {}

  listGuides(): Observable<GuideSummary[]> {
    return this.http.get<GuideSummary[]>(this.baseUrl);
  }

  upload(file: File): Observable<GuideSummary> {
    const formData = new FormData();
    formData.append('file', file, file.name);
    return this.http.post<GuideSummary>(this.baseUrl, formData);
  }

  deleteGuide(id: string): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${id}`);
  }

  /** Ré-embedding manuel forcé, même si le hash est inchangé (debug / changement de config de chunking). */
  reprocess(id: string): Observable<GuideSummary> {
    return this.http.post<GuideSummary>(`${this.baseUrl}/${id}/reprocess`, null);
  }
}
