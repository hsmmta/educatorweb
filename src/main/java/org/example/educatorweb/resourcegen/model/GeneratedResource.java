package org.example.educatorweb.resourcegen.model;

import org.example.educatorweb.common.model.ResourceType;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record GeneratedResource(
    String resourceId,
    ResourceType type,
    String knowledgePoint,
    String title,
    String content,
    Map<String, Object> metadata,
    Instant createdAt
) {
    public static GeneratedResource of(ResourceType type, String knowledgePoint, String title, String content) {
        return new GeneratedResource(UUID.randomUUID().toString(), type, knowledgePoint, title, content, Map.of(), Instant.now());
    }
}
