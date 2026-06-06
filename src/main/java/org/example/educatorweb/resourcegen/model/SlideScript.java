package org.example.educatorweb.resourcegen.model;

import java.util.List;

public record SlideScript(
    int index,
    String title,
    List<String> bulletPoints,
    String narration,
    String visualPrompt,
    int durationSeconds
) {}
