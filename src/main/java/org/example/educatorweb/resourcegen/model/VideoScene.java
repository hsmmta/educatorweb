package org.example.educatorweb.resourcegen.model;

public record VideoScene(
    int index,
    String description,
    String narration,
    String visualPrompt,
    String cameraAngle,
    int durationSeconds,
    String transition
) {}
