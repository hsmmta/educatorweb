package org.example.educatorweb.resourcegen.model;

import java.util.List;

public record VideoScript(
    String title,
    List<VideoScene> scenes,
    String style,
    int totalDurationSeconds
) {}
