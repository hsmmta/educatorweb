package org.example.educatorweb.resourcegen.model;

import java.util.List;

/**
 * AI-generated voiceover script with segment-level timing and annotation actions.
 * Output of the second DeepSeek LLM call in the new video pipeline.
 * Timing fields (start/speechEnd/end) are initial LLM estimates — the whiteboard
 * E renderer replaces them with measured TTS audio timing.
 */
public record NarrationScript(
    String topic,
    int targetDurationSec,
    List<ScriptSegment> segments
) {
    public record ScriptSegment(
        String id,
        double start,
        double speechEnd,
        double end,
        String caption,
        String boardId,
        String target,
        List<ScriptAction> actions
    ) {}

    public record ScriptAction(
        String type,
        String element,
        String spokenAnchor,
        double duration
    ) {}
}
