package org.example.educatorweb.knowledgegraph.model;

import java.util.List;

public record KnowledgeContext(
    List<String> prerequisites,
    List<String> successors,
    List<String> relatedConcepts,
    int difficultyLevel
) {}
