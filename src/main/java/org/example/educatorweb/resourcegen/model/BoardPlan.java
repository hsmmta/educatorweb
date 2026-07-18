package org.example.educatorweb.resourcegen.model;

import java.util.List;

/**
 * AI-generated whiteboard plan — what boards to create, what goes on each.
 * Output of the first DeepSeek LLM call in the new video pipeline.
 */
public record BoardPlan(
    String topic,
    String style,
    List<Board> boards
) {
    public record Board(
        String id,
        String title,
        String subtitle,
        List<BoardSection> sections
    ) {}

    public record BoardSection(
        String id,
        String title,
        List<String> items,
        List<String> annotations
    ) {}
}
