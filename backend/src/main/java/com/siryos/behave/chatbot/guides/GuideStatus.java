package com.siryos.behave.chatbot.guides;

public enum GuideStatus {
    /** Uploadé, en attente/en cours de chunking+embedding. */
    PENDING,
    /** Chunké et indexé dans PGVector, prêt à être utilisé par le chatbot. */
    PROCESSED,
    /** Le chunking/embedding a échoué (cf. Guide.lastError). */
    FAILED
}
