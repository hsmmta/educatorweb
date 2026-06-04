package org.example.educatorweb.common.model;

import java.util.List;

public record GenerateRequest(
    String studentId,
    String knowledgePoint,
    List<ResourceType> types
) {
    public GenerateRequest {
        if (studentId == null || studentId.isBlank()) {
            throw new IllegalArgumentException("studentId is required");
        }
        if (knowledgePoint == null || knowledgePoint.isBlank()) {
            throw new IllegalArgumentException("knowledgePoint is required");
        }
        if (types == null || types.isEmpty()) {
            types = List.of(ResourceType.values()); // default: all types
        }
    }
}
