package org.example.educatorweb.aitutor.model;

import java.time.Instant;
import java.util.List;

/**
 * Response body returned by the AI tutor after processing a question.
 */
public record ChatResponse(
    String conversationId,
    String answer,
    List<SourceSnippet> sources,
    Instant timestamp
) {
    public record SourceSnippet(String text, String source, double score) {}
}
