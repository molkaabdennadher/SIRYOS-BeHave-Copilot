package com.siryos.behave.chatbot.guides.dto;

import com.siryos.behave.chatbot.guides.Guide;
import com.siryos.behave.chatbot.guides.GuideStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Vue exposée à l'écran admin. On n'expose jamais le hash complet
 * (inutile côté UI) — seulement les 8 premiers caractères, à titre
 * indicatif/debug.
 */
public record GuideSummary(
        UUID id,
        String filename,
        GuideStatus status,
        int chunkCount,
        String contentHashShort,
        Instant uploadedAt,
        Instant lastProcessedAt,
        String lastError
) {
    public static GuideSummary from(Guide g) {
        String shortHash = g.getContentHash() != null && g.getContentHash().length() >= 8
                ? g.getContentHash().substring(0, 8)
                : g.getContentHash();
        return new GuideSummary(g.getId(), g.getFilename(), g.getStatus(), g.getChunkCount(),
                shortHash, g.getUploadedAt(), g.getLastProcessedAt(), g.getLastError());
    }
}
