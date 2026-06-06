package org.example.educatorweb.common.model;

import java.time.Instant;
import java.util.Map;

public record ProgressEvent(
    String requestId,
    String stage,
    String message,
    int progressPercent,
    Instant timestamp,
    Map<String, Object> payload
) {
    public ProgressEvent(String requestId, String stage, String message, int progressPercent) {
        this(requestId, stage, message, progressPercent, Instant.now(), null);
    }

    public ProgressEvent(String requestId, String stage, String message, int progressPercent,
                         Map<String, Object> payload) {
        this(requestId, stage, message, progressPercent, Instant.now(), payload);
    }
}
