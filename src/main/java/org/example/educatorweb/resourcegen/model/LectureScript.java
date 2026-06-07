package org.example.educatorweb.resourcegen.model;

import java.util.List;

public record LectureScript(
    String title,
    List<SlideScript> slides,
    String teacherName,
    int estimatedDurationSeconds
) {}
