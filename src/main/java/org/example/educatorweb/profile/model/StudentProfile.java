package org.example.educatorweb.profile.model;

import java.util.List;
import java.util.Map;

public record StudentProfile(
    D1_KnowledgeBase knowledgeBase,
    D2_CognitiveStyle cognitiveStyle,
    D3_ErrorPattern errorPattern,
    D4_LearningPace learningPace,
    D5_ContentPreference contentPreference,
    D6_GoalOrientation goalOrientation
) {
    public record D1_KnowledgeBase(String level, double confidence, Map<String, String> details) {}
    public record D2_CognitiveStyle(String type, double confidence) {}
    public record D3_ErrorPattern(List<String> tags, double confidence) {}
    public record D4_LearningPace(String type, double confidence) {}
    public record D5_ContentPreference(String type, Map<String, Double> ratio) {}
    public record D6_GoalOrientation(String type, double confidence) {}
}
