package org.example.educatorweb.common.model;

import java.time.Instant;

public record ProgressEvent(
    String requestId,
    String stage,
    String message,
    int progressPercent,
    Instant timestamp
) {
    public ProgressEvent(String requestId, String stage, String message, int progressPercent) {
        this(requestId, stage, message, progressPercent, Instant.now());
    }
}
