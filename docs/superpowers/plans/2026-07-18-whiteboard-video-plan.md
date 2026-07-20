# Whiteboard Video Generation — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 升级 VideoGenerator 从"静默幻灯片 MP4"为"手绘白板 + TTS 配音 + 镜头运镜"讲解视频。

**Architecture:** Java 层负责内容创作——DeepSeek 生成画板规划(boards.json)和旁白脚本(script.json)，Seedream 生成手绘白板图。然后通过 Python 桥接脚本调 whiteboard 的 D+E 渲染管线完成配音、运镜、合成。失败自动降级回旧逻辑。

**Tech Stack:** Java 17+ (records), Spring Boot, DeepSeek (LLM via ChatClient), Seedream (图片 via SeedanceVideoProvider), Python 3.10+, whiteboard D/E 模块, FFmpeg, edge-tts

---

## File Map

| 类型 | 文件 | 职责 |
|------|------|------|
| 新 | `resourcegen/model/BoardPlan.java` | 画板规划 record（Board / BoardSection） |
| 新 | `resourcegen/model/NarrationScript.java` | 旁白脚本 record（ScriptSegment / ScriptAction） |
| 新 | `resourcegen/infrastructure/WhiteboardPipelineRunner.java` | ProcessBuilder 封装，调桥接脚本，轮询等 mp4 |
| 新 | `scripts/whiteboard-bridge.py` | 我们的 JSON → whiteboard D 格式，串联 calibration→D→E |
| 改 | `resourcegen/agents/generators/VideoGenerator.java` | 重写 doGenerate()：两轮 LLM → 画图 → 桥接 → 读 mp4；失败降级 |
| 改 | `resourcegen/config/ResourceGenConfig.java` | 新增 WhiteboardPipelineRunner bean |
| 改 | `src/main/resources/application.yml` | 新增 `resourcegen.whiteboard.*` 配置项 |

---

### Task 1: Create BoardPlan model records

**Files:**
- Create: `src/main/java/org/example/educatorweb/resourcegen/model/BoardPlan.java`

- [ ] **Step 1: Create the record file**

```java
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
```

- [ ] **Step 2: Verify it compiles**

```bash
cd E:/educatorweb/educatorweb && mvn compile -pl . -q
```
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/org/example/educatorweb/resourcegen/model/BoardPlan.java
git commit -m "feat: add BoardPlan model for whiteboard video planning"
```

---

### Task 2: Create NarrationScript model records

**Files:**
- Create: `src/main/java/org/example/educatorweb/resourcegen/model/NarrationScript.java`

- [ ] **Step 1: Create the record file**

```java
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
```

- [ ] **Step 2: Verify it compiles**

```bash
cd E:/educatorweb/educatorweb && mvn compile -pl . -q
```
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/org/example/educatorweb/resourcegen/model/NarrationScript.java
git commit -m "feat: add NarrationScript model for whiteboard voiceover scripting"
```

---

### Task 3: Create WhiteboardPipelineRunner

**Files:**
- Create: `src/main/java/org/example/educatorweb/resourcegen/infrastructure/WhiteboardPipelineRunner.java`

- [ ] **Step 1: Create the runner class**

```java
package org.example.educatorweb.resourcegen.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Executes the whiteboard rendering pipeline (calibration → board package → video render)
 * via a Python bridge script, then reads the resulting preview.mp4 bytes.
 *
 * <p>This is a ProcessBuilder wrapper — all heavy lifting happens in Python/Node land.
 */
public class WhiteboardPipelineRunner {

    private static final Logger log = LoggerFactory.getLogger(WhiteboardPipelineRunner.class);
    private static final long TIMEOUT_MINUTES = 10;

    private final String pythonPath;
    private final String bridgeScript;
    private final String whiteboardRoot;

    public WhiteboardPipelineRunner(String pythonPath, String bridgeScript, String whiteboardRoot) {
        this.pythonPath = pythonPath;
        this.bridgeScript = bridgeScript;
        this.whiteboardRoot = whiteboardRoot;
        log.info("WhiteboardPipelineRunner: python={}, bridge={}, whiteboard={}",
            pythonPath, bridgeScript, whiteboardRoot);
    }

    /**
     * Run the full whiteboard pipeline on a prepared working directory.
     *
     * @param workDir directory containing boards.json, script.json, and images/ subdirectory
     * @return bytes of the output preview.mp4
     * @throws IOException if the pipeline fails or the output file is missing
     */
    public byte[] run(Path workDir) throws IOException, InterruptedException {
        Path videoPath = workDir.resolve("video/preview.mp4");

        log.info("Whiteboard pipeline starting for {}", workDir);
        long start = System.currentTimeMillis();

        ProcessBuilder pb = new ProcessBuilder(
            pythonPath, bridgeScript,
            "--work-dir", workDir.toAbsolutePath().toString(),
            "--whiteboard-root", whiteboardRoot
        );
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(true);  // merge stderr into stdout for capture

        Process process = pb.start();
        boolean finished = process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES);

        if (!finished) {
            process.destroyForcibly();
            throw new IOException("Whiteboard pipeline timed out after " + TIMEOUT_MINUTES + " minutes");
        }

        int exitCode = process.exitValue();

        // Read output for diagnostics
        String output = new String(process.getInputStream().readAllBytes());
        if (exitCode != 0) {
            log.error("Whiteboard pipeline failed (exit={}): {}", exitCode, output);
            throw new IOException("Whiteboard pipeline failed with exit code " + exitCode);
        }

        if (!Files.exists(videoPath) || Files.size(videoPath) == 0) {
            throw new IOException("Whiteboard pipeline completed but preview.mp4 is missing or empty");
        }

        long elapsed = System.currentTimeMillis() - start;
        byte[] bytes = Files.readAllBytes(videoPath);
        log.info("Whiteboard pipeline finished: {} bytes in {} ms", bytes.length, elapsed);
        return bytes;
    }

    /**
     * Check whether the whiteboard runtime dependencies are available.
     * Returns true if python + bridge script exist (fast sanity check).
     */
    public boolean isAvailable() {
        try {
            // Quick check: does the bridge script exist?
            if (!Files.exists(Path.of(bridgeScript))) {
                log.warn("Whiteboard bridge script not found: {}", bridgeScript);
                return false;
            }
            // Check python can at least be invoked
            ProcessBuilder pb = new ProcessBuilder(pythonPath, "--version");
            Process p = pb.start();
            return p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            log.warn("Whiteboard not available: {}", e.getMessage());
            return false;
        }
    }
}
```

- [ ] **Step 2: Verify it compiles**

```bash
cd E:/educatorweb/educatorweb && mvn compile -pl . -q
```
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/org/example/educatorweb/resourcegen/infrastructure/WhiteboardPipelineRunner.java
git commit -m "feat: add WhiteboardPipelineRunner for ProcessBuilder bridge execution"
```

---

### Task 4: Add whiteboard config bean and YAML

**Files:**
- Modify: `src/main/java/org/example/educatorweb/resourcegen/config/ResourceGenConfig.java`
- Modify: `src/main/resources/application.yml`

- [ ] **Step 1: Add WhiteboardPipelineRunner bean to ResourceGenConfig.java**

Add this bean method at line 157, just before the `// ---- Helpers ----` comment:

```java
    // ---- Whiteboard pipeline ----

    @Value("${resourcegen.whiteboard.python-path:python3}")
    private String whiteboardPythonPath;

    @Value("${resourcegen.whiteboard.bridge-script:scripts/whiteboard-bridge.py}")
    private String whiteboardBridgeScript;

    @Value("${resourcegen.whiteboard.root:${user.home}/.claude/skills/whiteboard-video}")
    private String whiteboardRoot;

    @Bean
    public WhiteboardPipelineRunner whiteboardPipelineRunner() {
        return new WhiteboardPipelineRunner(
            whiteboardPythonPath, whiteboardBridgeScript, whiteboardRoot);
    }
```

Also add the import at the top (after the existing infrastructure imports around line 8):

```java
import org.example.educatorweb.resourcegen.infrastructure.WhiteboardPipelineRunner;
```

- [ ] **Step 2: Add YAML config**

Add after the `seedance:` provider block in `application.yml` (after line 110):

```yaml
resourcegen:
  whiteboard:
    python-path: python3
    bridge-script: scripts/whiteboard-bridge.py
    root: ${user.home}/.claude/skills/whiteboard-video
```

- [ ] **Step 3: Verify it compiles**

```bash
cd E:/educatorweb/educatorweb && mvn compile -pl . -q
```
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/org/example/educatorweb/resourcegen/config/ResourceGenConfig.java src/main/resources/application.yml
git commit -m "feat: add WhiteboardPipelineRunner bean and whiteboard config"
```

---

### Task 5: Rewrite VideoGenerator.doGenerate()

**Files:**
- Modify: `src/main/java/org/example/educatorweb/resourcegen/agents/generators/VideoGenerator.java`
- Read reference: `src/main/java/org/example/educatorweb/resourcegen/infrastructure/SeedanceVideoProvider.java`

This is the core change. The new flow:
1. DeepSeek → BoardPlan JSON
2. Seedream generates one hand-drawn whiteboard image per board
3. DeepSeek → NarrationScript JSON
4. Write all files to temp dir
5. WhiteboardPipelineRunner.run() → byte[]
6. Store and return path

- [ ] **Step 1: Rewrite VideoGenerator.java**

```java
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

        // Clean up temp dir
        try { deleteRecursively(workDir); } catch (IOException ignored) {}

        return path;
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
            boardSummary.append(String.format("  Board '%s': %s\n", b.id(), b.title()));
            for (BoardPlan.BoardSection s : b.sections()) {
                boardSummary.append(String.format("    Section '%s': %s\n", s.id(), s.title()));
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
```

- [ ] **Step 2: Verify it compiles**

```bash
cd E:/educatorweb/educatorweb && mvn compile -pl . -q
```
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/org/example/educatorweb/resourcegen/agents/generators/VideoGenerator.java
git commit -m "feat: rewrite VideoGenerator with whiteboard pipeline + legacy fallback"
```

---

### Task 6: Create Python bridge script

**Files:**
- Create: `scripts/whiteboard-bridge.py`

This script converts our `boards.json` + `script.json` + `images/` into whiteboard D format, then runs calibration → D → E.

- [ ] **Step 1: Create the bridge script**

```python
#!/usr/bin/env python3
"""
Bridge script that converts EducatorWeb JSON output into whiteboard D/E input format
and runs the full calibration → board package → video render pipeline.

Usage:
  python3 whiteboard-bridge.py --work-dir <dir> --whiteboard-root <dir>

Input (in work-dir):
  boards.json       — BoardPlan with sections and items
  script.json       — NarrationScript with segments, actions, timing
  images/*.png      — Hand-drawn whiteboard images from Seedream

Output (in work-dir):
  video/preview.mp4 — Final rendered video
"""

import argparse
import json
import os
import subprocess
import sys
from pathlib import Path


def fail(msg: str):
    print(f"FATAL: {msg}", file=sys.stderr)
    sys.exit(1)


def run(cmd: list[str], cwd: Path, label: str) -> None:
    """Run a subprocess, fail on non-zero exit."""
    print(f"\n=== {label} ===")
    print(f"  CMD: {' '.join(cmd)}")
    result = subprocess.run(cmd, cwd=str(cwd), capture_output=True, text=True)
    if result.stdout:
        print(result.stdout[-2000:])  # last 2000 chars
    if result.stderr:
        print(result.stderr[-2000:], file=sys.stderr)
    if result.returncode != 0:
        fail(f"{label} failed with exit code {result.returncode}")


# ── Parse args ──────────────────────────────────────────────

parser = argparse.ArgumentParser(description="EducatorWeb → Whiteboard bridge")
parser.add_argument("--work-dir", required=True, help="Working directory with boards.json, script.json, images/")
parser.add_argument("--whiteboard-root", required=True, help="Path to installed whiteboard skill root")
args = parser.parse_args()

work_dir = Path(args.work_dir).resolve()
wb_root = Path(args.whiteboard_root).resolve()
runtime = wb_root / "runtime"

if not work_dir.is_dir():
    fail(f"work-dir does not exist: {work_dir}")
if not runtime.is_dir():
    fail(f"whiteboard runtime not found at {runtime} (is whiteboard installed?)")

print(f"Work dir:     {work_dir}")
print(f"Whiteboard:   {wb_root}")

# ── Load our JSON inputs ────────────────────────────────────

boards_path = work_dir / "boards.json"
script_path = work_dir / "script.json"

if not boards_path.exists():
    fail(f"boards.json not found in {work_dir}")
if not script_path.exists():
    fail(f"script.json not found in {work_dir}")

with open(boards_path) as f:
    boards_data = json.load(f)
with open(script_path) as f:
    script_data = json.load(f)

print(f"Loaded: {len(boards_data.get('boards', []))} boards, {len(script_data.get('segments', []))} segments")

# ═══════════════════════════════════════════════════════════
# Step 1: Build whiteboard D input structure
# ═══════════════════════════════════════════════════════════

# 1a. Create directory layout
infographic_dir = work_dir / "infographic" / "board_specs"
script_out_dir = work_dir / "script"
infographic_dir.mkdir(parents=True, exist_ok=True)
script_out_dir.mkdir(parents=True, exist_ok=True)

# 1b. Write infographic_plan.json
infographic_plan = {
    "topic": boards_data.get("topic", "Untitled"),
    "boards": []
}
for board in boards_data.get("boards", []):
    infographic_plan["boards"].append({
        "boardId": board["id"],
        "title": board.get("title", ""),
        "sourceSpec": f"infographic/board_specs/{board['id']}.board_spec.json"
    })

with open(work_dir / "infographic" / "infographic_plan.json", "w") as f:
    json.dump(infographic_plan, f, indent=2, ensure_ascii=False)

# 1c. Write per-board board_spec.json
for board in boards_data.get("boards", []):
    spec = {
        "id": board["id"],
        "title": board.get("title", ""),
        "subtitle": board.get("subtitle", ""),
        "canvas": {"width": 1920, "height": 1080},
        "sections": [],
        "elements": []
    }

    for sec in board.get("sections", []):
        spec["sections"].append({
            "id": sec["id"],
            "title": sec.get("title", ""),
            "items": sec.get("items", []),
            "actions": sec.get("annotations", [])
        })
        # Create explicit element entries for calibration
        for idx, item in enumerate(sec.get("items", [])):
            element_id = f"{sec['id']}-item-{idx}"
            spec["elements"].append({
                "id": element_id,
                "kind": "text_item",
                "text": item,
                "actions": sec.get("annotations", [])
            })
        # Also create a section-level element
        spec["elements"].append({
            "id": sec["id"],
            "kind": "section_header",
            "text": sec.get("title", ""),
            "actions": sec.get("annotations", [])
        })

    spec_path = infographic_dir / f"{board['id']}.board_spec.json"
    with open(spec_path, "w") as f:
        json.dump(spec, f, indent=2, ensure_ascii=False)

print(f"  Wrote {len(boards_data.get('boards', []))} board specs")

# 1d. Write voiceover_segments.json (mostly pass-through)
voiceover = {
    "topic": script_data.get("topic", boards_data.get("topic", "Untitled")),
    "targetDurationSec": script_data.get("targetDurationSec", 45),
    "segments": []
}

for seg in script_data.get("segments", []):
    vs_seg = {
        "id": seg["id"],
        "start": seg["start"],
        "speechEnd": seg["speechEnd"],
        "end": seg["end"],
        "caption": seg["caption"],
        "boardId": seg["boardId"],
        "target": seg.get("target", ""),
        "actions": []
    }
    for act in seg.get("actions", []):
        vs_seg["actions"].append({
            "type": act["type"],
            "element": act["element"],
            "spokenAnchor": act["spokenAnchor"],
            "anchorRatio": 0.4,
            "duration": act["duration"]
        })
    voiceover["segments"].append(vs_seg)

with open(script_out_dir / "voiceover_segments.json", "w") as f:
    json.dump(voiceover, f, indent=2, ensure_ascii=False)

print(f"  Wrote {len(voiceover['segments'])} voiceover segments")

# 1e. Write board_asset_manifest.json
asset_manifest = {
    "version": "0.1",
    "assetContract": {"allowedKinds": ["file"]},
    "boards": []
}

for board in boards_data.get("boards", []):
    board_id = board["id"]
    img_path = work_dir / "images" / f"{board_id}.png"
    if not img_path.exists():
        print(f"  WARNING: image not found for {board_id}, skipping")
        continue

    asset_manifest["boards"].append({
        "boardId": board_id,
        "title": board.get("title", ""),
        "asset": {
            "kind": "file",
            "uri": f"images/{board_id}.png",
            "width": 1920,
            "height": 1080
        }
    })

if not asset_manifest["boards"]:
    fail("No board images found — nothing to render")

with open(work_dir / "board_asset_manifest.json", "w") as f:
    json.dump(asset_manifest, f, indent=2, ensure_ascii=False)

print(f"  Asset manifest: {len(asset_manifest['boards'])} boards")

# ═══════════════════════════════════════════════════════════
# Step 2: Run whiteboard calibration (find element bboxes)
# ═══════════════════════════════════════════════════════════

calibrate_script = runtime / "hand-drawn-infographic-video-board" / "scripts" / "auto_calibrate.py"
if calibrate_script.exists():
    run([sys.executable, str(calibrate_script),
         "--project-dir", str(work_dir),
         "--provider", "mock"],
        cwd=work_dir, label="Auto-calibrate bboxes")
else:
    print("  Calibration script not found, using mock bbox estimates")
    # Write minimal calibration output so D can proceed
    calib_dir = work_dir / "calibration"
    calib_dir.mkdir(parents=True, exist_ok=True)
    for board in boards_data.get("boards", []):
        calib = {"boardId": board["id"], "elements": []}
        y = 100
        for sec in board.get("sections", []):
            for idx, item in enumerate(sec.get("items", [])):
                calib["elements"].append({
                    "id": f"{sec['id']}-item-{idx}",
                    "bbox": [100, y, 400, 40],
                    "text": item
                })
                y += 60
            calib["elements"].append({
                "id": sec["id"],
                "bbox": [60, y - len(sec.get("items", [])) * 60 - 20, 500, 50],
                "text": sec.get("title", "")
            })
            y += 80
        with open(calib_dir / f"{board['id']}.element_bboxes.json", "w") as f:
            json.dump(calib, f, indent=2, ensure_ascii=False)
    print(f"  Wrote mock calibration for {len(boards_data.get('boards', []))} boards")

# ═══════════════════════════════════════════════════════════
# Step 3: Run whiteboard D — generate board package
# ═══════════════════════════════════════════════════════════

d_script = runtime / "hand-drawn-infographic-video-board" / "scripts" / "generate_board_package.py"
board_dir = work_dir / "board_source_for_e"

run([sys.executable, str(d_script),
     "--project", str(work_dir),
     "--asset-manifest", str(work_dir / "board_asset_manifest.json"),
     "--voiceover", str(script_out_dir / "voiceover_segments.json"),
     "--output", str(board_dir),
     "--calibration-dir", str(work_dir / "calibration")],
    cwd=work_dir, label="D: Generate board package")

# ═══════════════════════════════════════════════════════════
# Step 4: Run whiteboard E — render video
# ═══════════════════════════════════════════════════════════

e_script = runtime / "whiteboard-infographic-video-renderer" / "scripts" / "render_multi_board_project.mjs"

run(["node", str(e_script),
     "--project-dir", str(work_dir),
     "--board-dir", str(board_dir),
     "--output-dir", str(work_dir / "video"),
     "--quality", "draft",
     "--fps", "24"],
    cwd=work_dir, label="E: Render video")

# ═══════════════════════════════════════════════════════════
# Step 5: Verify output
# ═══════════════════════════════════════════════════════════

preview = work_dir / "video" / "preview.mp4"
if preview.exists() and preview.stat().st_size > 0:
    print(f"\nSUCCESS: preview.mp4 ({preview.stat().st_size} bytes)")
    print(preview.resolve())
    sys.exit(0)
else:
    fail(f"preview.mp4 not found or empty at {preview}")
```

- [ ] **Step 2: Verify the script is syntactically valid**

```bash
python3 -c "import py_compile; py_compile.compile('E:/educatorweb/educatorweb/scripts/whiteboard-bridge.py', doraise=True)" && echo "OK"
```
Expected: OK

- [ ] **Step 3: Commit**

```bash
git add scripts/whiteboard-bridge.py
git commit -m "feat: add whiteboard bridge script (our JSON → D/E pipeline)"
```

---

### Task 7: End-to-end verification

> **Note:** This task requires the whiteboard tool to be installed on the server first (see section "Environment Setup" below). Run this AFTER environment setup.

**Files:**
- Modify: none (verification only)

- [ ] **Step 1: Verify Java compilation with all new code**

```bash
cd E:/educatorweb/educatorweb && mvn compile -pl . -q
```
Expected: BUILD SUCCESS

- [ ] **Step 2: Start the application and check logs for whiteboard config**

```bash
cd E:/educatorweb/educatorweb && mvn spring-boot:run 2>&1 | head -50
```
Look for: `WhiteboardPipelineRunner: python=python3, bridge=...` in startup logs.
Expected: WhiteboardPipelineRunner initialization message appears, with isAvailable=false if whiteboard not yet installed (app should still start).

- [ ] **Step 3: Install whiteboard tool on server**

```bash
# Clone if not already available
git clone https://github.com/zkbys/whiteboard.git /tmp/whiteboard-install
cd /tmp/whiteboard-install

# Install to Claude Code skills directory (we only need the runtime modules)
python3 scripts/install.py --target claude

# Verify installation
python3 "$HOME/.claude/skills/whiteboard-video/scripts/doctor.py" --json

# Install extra dependencies
pip install edge-tts
npm install -g hyperframes@0.6.99
```
Expected: doctor reports PASS for install and render categories.

- [ ] **Step 4: Trigger a video generation and verify the full pipeline runs**

Send a POST to the resource generation endpoint with `"types": ["VIDEO"]` for a simple topic like "什么是机器学习".

Check logs for:
```
Board plan: N boards
Board board-1: generated image (N bytes)
Narration script: N segments
Whiteboard pipeline starting for ...
Whiteboard pipeline finished: N bytes in N ms
Whiteboard video: N bytes → generated-resources/.../xxx.mp4
```

Expected: `preview.mp4` file is produced, playable, contains hand-drawn style images with TTS narration.

- [ ] **Step 5: Verify legacy fallback still works**

Rename or remove the bridge script temporarily, trigger video generation again.

Check logs for:
```
Whiteboard not available, using legacy slideshow pipeline
```

Expected: Old-style silent slideshow video is produced without error.

- [ ] **Step 6: Commit any final tweaks**

```bash
git status
git add -A
git commit -m "chore: final verification tweaks for whiteboard pipeline"
```

---

## Environment Setup (one-time)

Before Task 7 verification, run these on the server:

```bash
# 1. Install whiteboard tool
git clone https://github.com/zkbys/whiteboard.git /tmp/whiteboard-install
cd /tmp/whiteboard-install
python3 scripts/install.py --target claude

# 2. Install runtime dependencies
pip install edge-tts
npm install -g hyperframes@0.6.99

# 3. Verify
python3 "$HOME/.claude/skills/whiteboard-video/scripts/doctor.py" --json

# 4. Note the installed path for application.yml
# Default: ~/.claude/skills/whiteboard-video
```

If the server already has Python 3.10+, Node 20+, and ffmpeg (confirmed), only `edge-tts` and `hyperframes` are new pip/npm installs. The whiteboard tool itself is just a file copy (no compilation needed).
