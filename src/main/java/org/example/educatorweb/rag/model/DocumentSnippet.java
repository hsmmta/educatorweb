package org.example.educatorweb.rag.model;

public record DocumentSnippet(
    String content,
    String source,
    double score
) {}
