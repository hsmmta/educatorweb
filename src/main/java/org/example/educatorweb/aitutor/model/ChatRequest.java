package org.example.educatorweb.aitutor.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Request body for the AI tutor chat endpoint.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatRequest(
    String studentId,
    String question,
    /** Optional: pass an existing conversationId to continue a multi-turn conversation */
    String conversationId,
    /** Optional: enable web search for this request */
    Boolean webSearch
) {
    public ChatRequest {
        if (studentId == null || studentId.isBlank()) {
            throw new IllegalArgumentException("studentId is required");
        }
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("question is required");
        }
    }
}
