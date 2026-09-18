// Types partagés entre la page Chat et le Dashboard Admin.
// Alignés 1:1 sur les DTO Java du backend (GuideSummary, ChatSessionSummary, ChatMessageView)
// pour éviter toute divergence silencieuse entre front et back.

export type GuideStatus = 'PENDING' | 'PROCESSED' | 'FAILED';

/** Miroir de com.siryos.behave.chatbot.guides.dto.GuideSummary */
export interface GuideSummary {
  id: string;
  filename: string;
  status: GuideStatus;
  chunkCount: number;
  contentHashShort: string;
  uploadedAt: string;
  lastProcessedAt: string | null;
  lastError: string | null;
}

/** Miroir de com.siryos.behave.chatbot.history.dto.ChatSessionSummary */
export interface ChatSessionSummary {
  id: string;
  title: string;
  createdAt: string;
  updatedAt: string;
}

/** Miroir de com.siryos.behave.chatbot.history.dto.ChatMessageView */
export interface ChatMessageView {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  explanationLevel: string | null;
  createdAt: string;
}
