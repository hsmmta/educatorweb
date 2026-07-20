package org.example.educatorweb.resourcegen.agents.generators;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.educatorweb.common.model.ResourceType;
import org.example.educatorweb.resourcegen.config.ModelRegistry;
import org.example.educatorweb.resourcegen.infrastructure.*;
import org.example.educatorweb.resourcegen.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
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
        } catch (InterruptedException e) {
            // Do not run the legacy pipeline on an interrupted thread
            Thread.currentThread().interrupt();
            throw new RuntimeException("Video generation interrupted", e);
        } catch (Exception e) {
            log.warn("Whiteboard pipeline failed, falling back to legacy", e);
        }
        return doLegacyGenerate(state);
    }

    // ═══════════════════════════════════════════
    // New whiteboard pipeline
    // ═══════════════════════════════════════════

    private String doWhiteboardGenerate(GenerationState state) throws IOException, InterruptedException {
        Path workDir = Files.createTempDirectory("whiteboard-gen-");
        try {
            // Phase 1: DeepSeek generates board plan
            BoardPlan boardPlan = generateBoardPlan(state);
            log.info("Board plan: {} boards", boardPlan.boards().size());

            // Phase 2: DeepSeek generates narration script (before image generation,
            // so a cheap LLM failure doesn't waste expensive image spend)
            NarrationScript script = generateNarrationScript(boardPlan, state);
            log.info("Narration script: {} segments", script.segments().size());

            // Phase 3: Seedream generates hand-drawn whiteboard images
            // (single-board failure -> placeholder; all boards failed -> legacy)
            Path imagesDir = workDir.resolve("images");
            Files.createDirectories(imagesDir);
            int realImages = 0;
            for (BoardPlan.Board board : boardPlan.boards()) {
                byte[] image;
                try {
                    image = generateBoardImage(board);
                    realImages++;
                    log.info("Board {}: generated image ({} bytes)", board.id(), image.length);
                } catch (Exception e) {
                    log.warn("Board {} image generation failed, using placeholder: {}",
                        board.id(), e.getMessage());
                    image = placeholderBoardImage();
                }
                Files.write(imagesDir.resolve(board.id() + ".png"), image);
            }
            if (realImages == 0) {
                throw new IllegalStateException("All board images failed");
            }

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
            try {
                deleteRecursively(workDir);
            } catch (IOException e) {
                log.debug("Temp dir cleanup failed (files may be locked): {}", e.getMessage());
            }
        }
    }

    private BoardPlan generateBoardPlan(GenerationState state) {
        ModelProvider textProvider = registry.resolve(ResourceType.DOC);
        String prompt = buildBoardPlanPrompt(state);
        BoardPlan plan = tryParseBoardPlan(textProvider.chat(prompt));
        if (plan == null) {
            log.warn("Board plan invalid, retrying board plan generation");
            plan = tryParseBoardPlan(textProvider.chat(prompt));
        }
        if (plan == null) {
            throw new IllegalStateException("Board plan generation failed after retry");
        }
        return sanitizeBoardIds(plan);
    }

    /** Parse + structurally validate one LLM response; null means "bad response". */
    private BoardPlan tryParseBoardPlan(String response) {
        BoardPlan plan;
        try {
            plan = objectMapper.readValue(stripJsonMarkdown(response), BoardPlan.class);
        } catch (Exception e) {
            log.warn("Failed to parse BoardPlan: {}", e.getMessage());
            return null;
        }
        if (!hasValidStructure(plan)) {
            log.warn("BoardPlan missing boards or sections");
            return null;
        }
        return plan;
    }

    private boolean hasValidStructure(BoardPlan plan) {
        if (plan.boards() == null || plan.boards().isEmpty()) {
            return false;
        }
        for (BoardPlan.Board board : plan.boards()) {
            if (board == null || board.sections() == null || board.sections().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Board ids become image filenames and cross-reference keys — never trust LLM output.
     * Invalid ids are replaced with positional ones; duplicates get a positional suffix.
     */
    private BoardPlan sanitizeBoardIds(BoardPlan plan) {
        List<BoardPlan.Board> boards = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < plan.boards().size(); i++) {
            BoardPlan.Board board = plan.boards().get(i);
            String id = board.id();
            if (id == null || !id.matches("^[a-z0-9-]+$")) {
                id = "board-" + (i + 1);
            }
            while (!seen.add(id)) {
                id = id + "-" + (i + 1);
            }
            boards.add(id.equals(board.id()) ? board
                : new BoardPlan.Board(id, board.title(), board.subtitle(), board.sections()));
        }
        return new BoardPlan(plan.topic(), plan.style(), boards);
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

    private byte[] generateBoardImage(BoardPlan.Board board) {
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

    /** Blank whiteboard-colored 1920x1080 PNG used when a single board's image generation fails. */
    private byte[] placeholderBoardImage() throws IOException {
        BufferedImage img = new BufferedImage(1920, 1080, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(245, 242, 235));
        g.fillRect(0, 0, 1920, 1080);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private NarrationScript generateNarrationScript(BoardPlan boardPlan, GenerationState state) {
        ModelProvider textProvider = registry.resolve(ResourceType.DOC);
        String prompt = buildNarrationPrompt(boardPlan, state);
        NarrationScript script = tryParseNarrationScript(textProvider.chat(prompt));
        if (script == null) {
            log.warn("Narration script invalid, retrying narration script generation");
            script = tryParseNarrationScript(textProvider.chat(prompt));
        }
        if (script == null) {
            throw new IllegalStateException("Narration script generation failed after retry");
        }
        return script;
    }

    /** Parse + validate one LLM response; null means "bad response". */
    private NarrationScript tryParseNarrationScript(String response) {
        NarrationScript script;
        try {
            script = objectMapper.readValue(stripJsonMarkdown(response), NarrationScript.class);
        } catch (Exception e) {
            log.warn("Failed to parse NarrationScript: {}", e.getMessage());
            return null;
        }
        if (script.segments() == null || script.segments().isEmpty()) {
            log.warn("NarrationScript has no segments");
            return null;
        }
        return script;
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
        log.info("VideoGenerator legacy: produced {} bytes", mp4Bytes.length);
        return fileStorage.store(state.requestId(), mp4Bytes,
            sanitizeFilename(state.knowledgePoint()) + ".mp4");
    }

    /** Keep old VideoScript generation for legacy fallback. */
    private VideoScript generateVideoScript(GenerationState state) {
        ModelProvider textProvider = registry.resolve(ResourceType.DOC);
        String prompt = buildLegacyScriptPrompt(state);
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

    private String buildLegacyScriptPrompt(GenerationState state) {
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
    // Shared helpers
    // ═══════════════════════════════════════════

    private String stripJsonMarkdown(String response) {
        String s = response.trim();
        if (s.startsWith("```")) {
            s = s.replaceFirst("^```[a-zA-Z]*\\s*", "").replaceFirst("```\\s*$", "").trim();
        }
        return s;
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
