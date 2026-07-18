package org.example.educatorweb.resourcegen.agents.generators;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.educatorweb.common.model.ResourceType;
import org.example.educatorweb.resourcegen.config.ModelRegistry;
import org.example.educatorweb.resourcegen.infrastructure.*;
import org.example.educatorweb.resourcegen.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.io.*;
import java.nio.file.*;
import java.util.*;

@Component
public class VideoGenerator extends AbstractGenerator {
    private static final Logger log = LoggerFactory.getLogger(VideoGenerator.class);
    private final ObjectMapper objectMapper;
    private final VideoProvider videoProvider;
    private final VideoAssembler assembler;
    private final FileStorageService fileStorage;
    private final WhiteboardPipelineRunner whiteboardRunner;

    public VideoGenerator(ObjectMapper objectMapper, VideoProvider videoProvider,
                          VideoAssembler assembler, FileStorageService fileStorage,
                          ModelRegistry registry, WhiteboardPipelineRunner whiteboardRunner) {
        super(registry, ResourceType.VIDEO);
        this.objectMapper = objectMapper;
        this.videoProvider = videoProvider;
        this.assembler = assembler;
        this.fileStorage = fileStorage;
        this.whiteboardRunner = whiteboardRunner;
    }

    @Override
    protected String doGenerate(GenerationState state) {
        // Try whiteboard pipeline first; fall back to old slideshow on any failure
        try {
            if (whiteboardRunner.isAvailable()) {
                return doWhiteboardGenerate(state);
            }
            log.info("Whiteboard not available, using legacy slideshow pipeline");
        } catch (Exception e) {
            log.warn("Whiteboard pipeline failed, falling back to legacy: {}", e.getMessage());
        }
        return doLegacyGenerate(state);
    }

    // ═══════════════════════════════════════════
    // New whiteboard pipeline
    // ═══════════════════════════════════════════

    private String doWhiteboardGenerate(GenerationState state) throws Exception {
        Path workDir = Files.createTempDirectory("whiteboard-gen-");
        try {
            // Phase 1: DeepSeek generates board plan
            BoardPlan boardPlan = generateBoardPlan(state);
            log.info("Board plan: {} boards", boardPlan.boards().size());

            // Phase 2: Seedream generates hand-drawn whiteboard images
            Path imagesDir = workDir.resolve("images");
            Files.createDirectories(imagesDir);
            for (BoardPlan.Board board : boardPlan.boards()) {
                byte[] image = generateBoardImage(board, state.knowledgePoint());
                Files.write(imagesDir.resolve(board.id() + ".png"), image);
                log.info("Board {}: generated image ({} bytes)", board.id(), image.length);
            }

            // Phase 3: DeepSeek generates narration script
            NarrationScript script = generateNarrationScript(boardPlan, state);
            log.info("Narration script: {} segments", script.segments().size());

            // Phase 4: Write JSON files for the bridge script
            objectMapper.writerWithDefaultPrettyPrinter()
                .writeValue(workDir.resolve("boards.json").toFile(), boardPlan);
            objectMapper.writerWithDefaultPrettyPrinter()
                .writeValue(workDir.resolve("script.json").toFile(), script);

            // Phase 5: Run whiteboard pipeline (calibrate → D → E → mp4)
            byte[] mp4Bytes = whiteboardRunner.run(workDir);

            // Phase 6: Store and return path
            String path = fileStorage.store(state.requestId(), mp4Bytes,
                sanitizeFilename(state.knowledgePoint()) + ".mp4");
            log.info("Whiteboard video: {} bytes → {}", mp4Bytes.length, path);
            return path;
        } finally {
            try { deleteRecursively(workDir); } catch (IOException ignored) {}
        }
    }

    private BoardPlan generateBoardPlan(GenerationState state) {
        ModelProvider textProvider = registry.resolve(ResourceType.DOC);
        String prompt = buildBoardPlanPrompt(state);
        String response = textProvider.chat(prompt);
        try {
            response = stripJsonMarkdown(response);
            return objectMapper.readValue(response, BoardPlan.class);
        } catch (Exception e) {
            log.warn("Failed to parse BoardPlan, using fallback: {}", e.getMessage());
            return buildFallbackBoardPlan(state);
        }
    }

    private String buildBoardPlanPrompt(GenerationState state) {
        return String.format("""
            You are designing a hand-drawn whiteboard explainer video for education.
            Topic: %s

            Design 1-3 whiteboard canvases. Each canvas should contain 2-4 sections
            with short text items (bullet points, keywords, simple formulas).
            Think of it like a teacher drawing on a whiteboard — sparse, visual, key concepts only.

            Output a JSON BoardPlan:
            {
              "topic": "...",
              "style": "hand-drawn-whiteboard",
              "boards": [
                {
                  "id": "board-1",
                  "title": "...",
                  "subtitle": "...",
                  "sections": [
                    {
                      "id": "a-short-id",
                      "title": "Section title",
                      "items": ["point 1", "point 2"],
                      "annotations": ["circle", "underline"]
                    }
                  ]
                }
              ]
            }

            Rules:
            - id values must be lowercase letters, digits, and hyphens only (no Chinese, no spaces).
            - annotations: use only "circle", "underline", "box", "check", "strike".
            - Each board should be self-contained and visually balanced.
            - Section titles and items should be in Chinese.
            - For a %s video, 1-2 boards are usually enough.

            Output ONLY the JSON, no markdown fences or extra text.
            """, state.knowledgePoint(), state.knowledgePoint());
    }

    private byte[] generateBoardImage(BoardPlan.Board board, String topic) {
        StringBuilder visualDesc = new StringBuilder();
        visualDesc.append("Hand-drawn whiteboard infographic about \"").append(board.title()).append("\". ");
        for (BoardPlan.BoardSection sec : board.sections()) {
            visualDesc.append(sec.title()).append(": ");
            visualDesc.append(String.join(", ", sec.items())).append(". ");
        }

        String prompt = String.format("""
            %s
            STYLE REQUIREMENTS:
            - Hand-drawn sketch style on a whiteboard or dark chalkboard
            - Organic, slightly rough lines (not clean vector graphics)
            - Handwritten-style Chinese text labels, clear and readable
            - Professional infographic layout with clear section separation
            - Arrows or connectors between related concepts
            - No photo-realistic elements, no 3D renders
            - Warm, engaging educational illustration feel
            - Slight paper texture or chalk dust texture in background
            """, visualDesc.toString());

        return videoProvider.generateImage(prompt);
    }

    private NarrationScript generateNarrationScript(BoardPlan boardPlan, GenerationState state) {
        ModelProvider textProvider = registry.resolve(ResourceType.DOC);
        String prompt = buildNarrationPrompt(boardPlan, state);
        String response = textProvider.chat(prompt);
        try {
            response = stripJsonMarkdown(response);
            return objectMapper.readValue(response, NarrationScript.class);
        } catch (Exception e) {
            log.warn("Failed to parse NarrationScript, using fallback: {}", e.getMessage());
            return buildFallbackNarration(boardPlan, state);
        }
    }

    private String buildNarrationPrompt(BoardPlan boardPlan, GenerationState state) {
        // Build a compact board summary for the prompt
        StringBuilder boardSummary = new StringBuilder();
        for (BoardPlan.Board b : boardPlan.boards()) {
            boardSummary.append(String.format("  Board '%s': %s%n", b.id(), b.title()));
            for (BoardPlan.BoardSection s : b.sections()) {
                boardSummary.append(String.format("    Section '%s': %s%n", s.id(), s.title()));
            }
        }

        return String.format("""
            You are writing a teacher's voiceover script for a whiteboard explainer video.
            Topic: %s

            Available boards and their elements:
            %s

            Write a narration script that walks through the boards, pointing at and annotating
            elements as the teacher explains them. Output JSON:

            {
              "topic": "...",
              "targetDurationSec": 45,
              "segments": [
                {
                  "id": "seg-intro",
                  "start": 0.0,
                  "speechEnd": 6.0,
                  "end": 6.2,
                  "caption": "The Chinese narration text the teacher will say...",
                  "boardId": "board-1",
                  "target": "section-id",
                  "actions": [
                    {
                      "type": "circle",
                      "element": "section-id",
                      "spokenAnchor": "keywords from caption",
                      "duration": 0.8
                    }
                  ]
                }
              ]
            }

            Rules:
            - One segment per logical explanation unit (typically one per board section).
            - start/speechEnd/end: estimate timing at ~4 Chinese chars/second.
            - end should be 0.2-0.3s after speechEnd for breathing room.
            - caption: the exact Chinese narration text for this segment.
            - boardId must match a board id from the board plan above.
            - target: the section id being explained.
            - element in actions: can be the section id or "sectionId-item-N" (0-indexed).
            - spokenAnchor: 2-4 character substring from caption that triggers the annotation.
            - type: "circle", "underline", "box", "check", or "strike".
            - Use 1-2 actions per segment.

            Output ONLY the JSON, no markdown fences or extra text.
            """, state.knowledgePoint(), boardSummary.toString());
    }

    // ═══════════════════════════════════════════
    // Legacy slideshow pipeline (fallback)
    // ═══════════════════════════════════════════

    private String doLegacyGenerate(GenerationState state) {
        VideoScript script = generateVideoScript(state);

        List<byte[]> slideImages = new ArrayList<>();
        for (VideoScene scene : script.scenes()) {
            try {
                String imagePrompt = buildEducationalImagePrompt(scene, state.knowledgePoint());
                byte[] image = videoProvider.generateImage(imagePrompt);
                slideImages.add(image);
                log.info("Slide {}: generated image ({} bytes)", scene.index(), image.length);
            } catch (Exception e) {
                log.warn("Slide {} image generation failed: {}", scene.index(), e.getMessage());
                slideImages.add(new byte[0]);
            }
        }

        List<byte[]> validImages = slideImages.stream()
            .filter(img -> img.length > 0)
            .toList();

        if (validImages.isEmpty()) {
            log.warn("No valid slide images generated — returning placeholder");
            return fileStorage.store(state.requestId(), new byte[0],
                sanitizeFilename(state.knowledgePoint()) + ".mp4");
        }

        byte[] mp4Bytes = assembler.imagesToVideo(validImages, script.scenes());
        return fileStorage.store(state.requestId(), mp4Bytes,
            sanitizeFilename(state.knowledgePoint()) + ".mp4");
    }

    // ═══════════════════════════════════════════
    // Shared helpers
    // ═══════════════════════════════════════════

    private String stripJsonMarkdown(String response) {
        String s = response.trim();
        if (s.startsWith("```")) {
            s = s.replaceAll("```json\\s*", "").replaceAll("```\\s*$", "").trim();
        }
        return s;
    }

    /** Keep old VideoScript generation for legacy fallback. */
    private VideoScript generateVideoScript(GenerationState state) {
        ModelProvider textProvider = registry.resolve(ResourceType.DOC);
        String prompt = buildPrompt(state);
        String response = textProvider.chat(prompt);
        try {
            response = stripJsonMarkdown(response);
            return objectMapper.readValue(response, VideoScript.class);
        } catch (Exception e) {
            log.warn("Failed to parse VideoScript, using fallback: {}", e.getMessage());
            return buildFallbackScript(state);
        }
    }

    private String buildEducationalImagePrompt(VideoScene scene, String topic) {
        String visual = scene.visualPrompt();
        if (visual == null || visual.isBlank() || visual.startsWith("unused")) {
            visual = scene.description();
        }
        return String.format(
            "%s. " +
            "CRITICAL: This is a university lecture slide. " +
            "All text must be CLEAR, LEGIBLE Chinese text with NO garbled characters. " +
            "Mathematical formulas must be rendered correctly. " +
            "Use clean professional layout, dark blue background, white text. " +
            "No abstract patterns or meaningless scribbles.",
            visual);
    }

    private String buildPrompt(GenerationState state) {
        return """
            You are a professional educator creating an educational video script.
            Topic: %s

            For each slide (4-6 slides), provide:
            - description: what this slide explains (Chinese)
            - narration: teacher's spoken words (Chinese)
            - visualPrompt: DETAILED visual description in ENGLISH for AI image generation.
              Describe exactly what should appear: layout, colors, text content,
              formulas, diagrams specific to %s. Be specific — "A slide titled 'Lagrangian Duality'
              showing the primal optimization problem on the left with constraint equations,
              and the Lagrangian function on the right with Lagrange multipliers α.
              Dark blue background, white text, clean academic style."
            - durationSeconds: 20-30 seconds

            Output JSON: {"title":"...","scenes":[{...}]}
            Output ONLY the JSON, no markdown.
            """.formatted(state.knowledgePoint(), state.knowledgePoint());
    }

    private VideoScript buildFallbackScript(GenerationState state) {
        return new VideoScript(
            state.knowledgePoint(),
            List.of(
                new VideoScene(1, "课程介绍",
                    "欢迎来到本节课程，我们将学习" + state.knowledgePoint(),
                    "Educational title card", "wide", 10, "fade"),
                new VideoScene(2, "核心概念",
                    "让我们理解核心概念", "Key concepts diagram", "wide", 30, "cut"),
                new VideoScene(3, "总结",
                    "本节课要点回顾", "Summary with key takeaways", "wide", 10, "fade")
            ),
            "Realistic", 80
        );
    }

    // ═══════════════════════════════════════════
    // Fallback builders for whiteboard mode
    // ═══════════════════════════════════════════

    private BoardPlan buildFallbackBoardPlan(GenerationState state) {
        return new BoardPlan(
            state.knowledgePoint(),
            "hand-drawn-whiteboard",
            List.of(
                new BoardPlan.Board("board-1", state.knowledgePoint(), "核心概念",
                    List.of(
                        new BoardPlan.BoardSection("concept", "核心概念",
                            List.of("定义", "关键要素", "应用场景"),
                            List.of("circle", "underline"))
                    ))
            )
        );
    }

    private NarrationScript buildFallbackNarration(BoardPlan plan, GenerationState state) {
        if (plan.boards().isEmpty()) {
            return new NarrationScript(state.knowledgePoint(), 30, List.of());
        }
        BoardPlan.Board firstBoard = plan.boards().get(0);
        return new NarrationScript(
            state.knowledgePoint(),
            30,
            List.of(
                new NarrationScript.ScriptSegment("intro", 0, 5.0, 5.2,
                    "欢迎来到本节课程，我们将学习" + state.knowledgePoint() + "。",
                    firstBoard.id(), "concept",
                    List.of(new NarrationScript.ScriptAction("circle", "concept", state.knowledgePoint(), 0.8))),
                new NarrationScript.ScriptSegment("detail", 5.2, 15.0, 15.3,
                    "让我们深入了解" + state.knowledgePoint() + "的核心概念和应用。",
                    firstBoard.id(), "concept",
                    List.of(new NarrationScript.ScriptAction("underline", "concept", "核心概念", 0.8))),
                new NarrationScript.ScriptSegment("summary", 15.3, 25.0, 25.0,
                    "以上就是本节关于" + state.knowledgePoint() + "的全部内容。",
                    firstBoard.id(), "concept",
                    List.of())
            )
        );
    }

    private String sanitizeFilename(String name) {
        return name.replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fff]", "_")
            .substring(0, Math.min(name.length(), 50));
    }

    private void deleteRecursively(Path dir) throws IOException {
        if (Files.isDirectory(dir)) {
            try (var stream = Files.list(dir)) {
                stream.forEach(p -> {
                    try { deleteRecursively(p); } catch (IOException ignored) {}
                });
            }
        }
        Files.deleteIfExists(dir);
    }

    @Override
    protected String getFormatHint() { return "MP4 (whiteboard infographic video with TTS narration)"; }
}
