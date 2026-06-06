package org.example.educatorweb.resourcegen.model;

import org.example.educatorweb.common.model.ResourceType;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public record ResourceBlueprint(
    String title,
    String summary,
    List<BlueprintSection> sections,
    Map<ResourceType, ResourcePlan> resourcePlans,
    List<DifficultyAdjustment> adjustments,
    Instant createdAt
) {
    public record BlueprintSection(String heading, int depth, String keyPoints, List<BlueprintSection> children) {}
    public record ResourcePlan(String promptFocus, List<String> keyPoints, String formatHint) {}
    public record DifficultyAdjustment(String dimension, String description, String effect) {}
}
