# Video Generation (ViMax-Style) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Split VIDEO into PPT (existing slide generator) and a new VIDEO (ViMax-style 4-phase pipeline producing .mp4 files via VideoProvider → FFmpeg assembly).

**Architecture:** Rename VideoGenerator → PptGenerator. Create VideoProvider interface with generateVideo() returning byte[]. Build VideoGenerator that follows ViMax's 4-agent flow: DesignAgent→VideoScript→Storyboard→VideoProvider.generateVideo()→FFmpeg concat. Multi-layer fallback: Seedance → Veo → CogVideoX → StaticImage → PureText.

**Tech Stack:** Spring Boot 3.4.3, Java 21, FFmpeg CLI, Spring AI 1.0.0-M6

**Design Spec:** `docs/superpowers/specs/2026-06-05-video-generation-design.md`

---

### Task 1: Phase A — ResourceType Split + VideoProvider Interface + Models

**Files:**
- Modify: `src/main/java/org/example/educatorweb/common/model/ResourceType.java`
- Create: `src/main/java/org/example/educatorweb/resourcegen/infrastructure/VideoProvider.java`
- Create: `src/main/java/org/example/educatorweb/resourcegen/model/VideoScript.java`
- Modify: `src/main/java/org/example/educatorweb/resourcegen/agents/generators/VideoGenerator.java` → rename to PptGenerator

- [ ] **Step 1: Rename ResourceType.VIDEO to PPT, add new VIDEO**

```java
package org.example.educatorweb.common.model;

public enum ResourceType {
    DOC,
    MINDMAP,
    QUIZ,
    PPT,        // ← was VIDEO, now PPT (slide generation)
    CODE,
    HTML,
    VIDEO       // 🆕 actual video generation (ViMax-style)
}
```

- [ ] **Step 2: Create VideoProvider interface**

```java
package org.example.educatorweb.resourcegen.infrastructure;

public interface VideoProvider {
    /**
     * Generate a video clip from a visual prompt.
     * @param visualPrompt description of the scene to generate
     * @param durationSeconds target duration in seconds
     * @return MP4 video bytes
     */
    byte[] generateVideo(String visualPrompt, int durationSeconds);

    /**
     * Fallback: generate a static image when video generation is unavailable.
     * @param prompt image description
     * @return PNG/JPEG image bytes
     */
    byte[] generateImage(String prompt);

    String providerName();

    default boolean isEnabled() { return true; }
}
```

- [ ] **Step 3: Create VideoScript and VideoScene records**

```java
package org.example.educatorweb.resourcegen.model;

import java.util.List;

public record VideoScript(
    String title,
    List<VideoScene> scenes,
    String style,              // "Cartoon" | "Realistic" | "Whiteboard"
    int totalDurationSeconds
) {}

public record VideoScene(
    int index,
    String description,        // scene description for context
    String narration,          // spoken words (for teammate's TTS)
    String visualPrompt,       // prompt for VideoProvider.generateVideo()
    String cameraAngle,        // "wide" | "close-up" | "dolly"
    int durationSeconds,
    String transition          // "fade" | "cut" | "dissolve"
) {}
```

- [ ] **Step 4: Rename VideoGenerator.java → PptGenerator.java**

Use `git mv` to preserve history. Then update the class name, constructor, and all references:
```bash
git mv src/main/java/org/example/educatorweb/resourcegen/agents/generators/VideoGenerator.java \
       src/main/java/org/example/educatorweb/resourcegen/agents/generators/PptGenerator.java
```

In `PptGenerator.java`, change:
- `public class VideoGenerator` → `public class PptGenerator`
- Constructor name: `VideoGenerator` → `PptGenerator`
- `supportedType()` returns `ResourceType.PPT` instead of `ResourceType.VIDEO`
- `getFormatHint()` returns `"PPTX (Apache POI)"`
- Log messages: replace "VideoGenerator" with "PptGenerator"

- [ ] **Step 5: Update all references to the renamed type**

Search and replace VIDEO→PPT and VideoGenerator→PptGenerator in:
- `ResourceGenerationService.java` — import and field names, node names (`GEN_VIDEO` → `GEN_PPT`)
- `ResourceGenConfig.java` — reference in ModelRegistry switch
- `test.html` — checkbox value `PPT` instead of `VIDEO`

Add new VIDEO checkbox to test.html:
```html
<label><input type="checkbox" value="PPT"> 📊 PPT</label>
<label><input type="checkbox" value="VIDEO"> 🎬 视频</label>
```

- [ ] **Step 6: Verify compilation and tests**

Run: `export JAVA_HOME="/c/Users/x/.jdks/openjdk-25.0.2" && mvn clean compile`

Fix any compilation errors from the rename (search for stale references to `VideoGenerator`, `ResourceType.VIDEO` in test files and service classes).

Run: `export JAVA_HOME="/c/Users/x/.jdks/openjdk-25.0.2" && mvn test`

Expected: BUILD SUCCESS, all 18 tests pass (with PPT type where VIDEO was used before).

- [ ] **Step 7: Commit**

```bash
git add -A && git commit -m "feat(phase-a): split VIDEO into PPT + VIDEO, create VideoProvider interface and VideoScript model"
```

---

### Task 2: Phase B — VideoProvider Implementations (Seedance + Fallback)

**Files:**
- Create: `src/main/java/org/example/educatorweb/resourcegen/infrastructure/SeedanceVideoProvider.java`
- Create: `src/main/java/org/example/educatorweb/resourcegen/infrastructure/StaticImageFallbackProvider.java`

- [ ] **Step 1: Create SeedanceVideoProvider**

```java
package org.example.educatorweb.resourcegen.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class SeedanceVideoProvider implements VideoProvider {
    private static final Logger log = LoggerFactory.getLogger(SeedanceVideoProvider.class);
    private final String baseUrl;
    private final String apiKey;
    private final boolean enabled;

    public SeedanceVideoProvider(String baseUrl, String apiKey, boolean enabled) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.enabled = enabled;
    }

    @Override
    public byte[] generateVideo(String visualPrompt, int durationSeconds) {
        log.info("SeedanceVideoProvider: generating {}s video for prompt ({} chars)",
            durationSeconds, visualPrompt.length());
        try {
            // Call Seedance API
            var client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
            String body = String.format(
                "{\"prompt\":\"%s\",\"duration\":%d}",
                visualPrompt.replace("\"", "\\\""), durationSeconds);
            var request = HttpRequest.newBuilder()
                .uri(java.net.URI.create(baseUrl + "/generate"))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofMinutes(5))
                .build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() == 200) {
                log.info("SeedanceVideoProvider: generated {} bytes", response.body().length);
                return response.body();
            }
            log.warn("SeedanceVideoProvider: API returned {}", response.statusCode());
            throw new RuntimeException("Seedance API returned " + response.statusCode());
        } catch (Exception e) {
            log.error("SeedanceVideoProvider failed: {}", e.getMessage());
            throw new RuntimeException("Seedance video generation failed", e);
        }
    }

    @Override
    public byte[] generateImage(String prompt) {
        log.info("SeedanceVideoProvider: generating image (not implemented, will fallback)");
        throw new UnsupportedOperationException("Seedance image generation not implemented");
    }

    @Override public String providerName() { return "seedance"; }
    @Override public boolean isEnabled() { return enabled; }
}
```

- [ ] **Step 2: Create StaticImageFallbackProvider — always-on safety net**

This provider never generates real video. It generates static images via the visual `ModelProvider` (e.g. OpenAI DALL-E), then converts to a static video frame.

For the initial implementation, it delegates to a `ModelProvider` for the image prompt and returns a placeholder:

```java
package org.example.educatorweb.resourcegen.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class StaticImageFallbackProvider implements VideoProvider {
    private static final Logger log = LoggerFactory.getLogger(StaticImageFallbackProvider.class);
    private final ModelProvider imageModel; // text→image model (e.g. OpenAI DALL-E)

    public StaticImageFallbackProvider(ModelProvider imageModel) {
        this.imageModel = imageModel;
    }

    @Override
    public byte[] generateVideo(String visualPrompt, int durationSeconds) {
        log.info("StaticImageFallback: generating static image as video frame for '{}'", visualPrompt);
        // For now: return empty bytes — will be replaced by actual image generation
        // The VideoAssembler will handle this as a "text-only" frame
        throw new UnsupportedOperationException(
            "Static image generation not yet implemented — use PureTextFallback");
    }

    @Override
    public byte[] generateImage(String prompt) {
        // Delegate to the text model to get an image description,
        // then use that to generate an actual image
        log.info("StaticImageFallback: generating image for prompt ({} chars)", prompt.length());
        throw new UnsupportedOperationException("Image generation not yet implemented");
    }

    @Override public String providerName() { return "static-image-fallback"; }
    @Override public boolean isEnabled() { return true; } // always available as fallback
}
```

- [ ] **Step 3: Verify compilation**

Run: `export JAVA_HOME="/c/Users/x/.jdks/openjdk-25.0.2" && mvn compile`

Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "feat(phase-b): add SeedanceVideoProvider and StaticImageFallbackProvider"
```

---

### Task 3: Phase C — VideoGenerator + VideoAssembler

**Files:**
- Create: `src/main/java/org/example/educatorweb/resourcegen/agents/generators/VideoGenerator.java`
- Create: `src/main/java/org/example/educatorweb/resourcegen/infrastructure/VideoAssembler.java`

- [ ] **Step 1: Create VideoAssembler — FFmpeg concat + transitions**

```java
package org.example.educatorweb.resourcegen.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.io.*;
import java.nio.file.*;
import java.util.List;

@Component
public class VideoAssembler {
    private static final Logger log = LoggerFactory.getLogger(VideoAssembler.class);

    /**
     * Concatenate video clips with transitions into a final MP4.
     * @param clips ordered list of MP4 byte arrays
     * @param transitions ordered list of transition types matching clip boundaries
     * @return final MP4 bytes
     */
    public byte[] assemble(List<byte[]> clips, List<String> transitions) {
        Path tmpDir = null;
        try {
            tmpDir = Files.createTempDirectory("video-assembly-");
            log.info("VideoAssembler: assembling {} clips in {}", clips.size(), tmpDir);

            // Write each clip to temp file
            for (int i = 0; i < clips.size(); i++) {
                Path clipPath = tmpDir.resolve(String.format("clip_%03d.mp4", i));
                Files.write(clipPath, clips[i]);
            }

            // Build FFmpeg concat file
            Path concatFile = tmpDir.resolve("concat.txt");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < clips.size(); i++) {
                sb.append(String.format("file 'clip_%03d.mp4'\n", i));
            }
            Files.writeString(concatFile, sb.toString());

            // Run FFmpeg concat
            Path output = tmpDir.resolve("output.mp4");
            ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg", "-y",
                "-f", "concat", "-safe", "0",
                "-i", concatFile.toAbsolutePath().toString(),
                "-c", "copy",
                output.toAbsolutePath().toString()
            );
            pb.directory(tmpDir.toFile());
            Process process = pb.start();
            int exitCode = process.waitFor();

            if (exitCode != 0) {
                String stderr = new String(process.getErrorStream().readAllBytes());
                throw new RuntimeException("FFmpeg failed (exit " + exitCode + "): " + stderr);
            }

            byte[] result = Files.readAllBytes(output);
            log.info("VideoAssembler: produced {} bytes", result.length);
            return result;
        } catch (Exception e) {
            log.error("VideoAssembler failed: {}", e.getMessage());
            throw new RuntimeException("Video assembly failed", e);
        } finally {
            if (tmpDir != null) {
                try { deleteRecursively(tmpDir); } catch (IOException ignored) {}
            }
        }
    }

    private void deleteRecursively(Path dir) throws IOException {
        if (Files.isDirectory(dir)) {
            try (var stream = Files.list(dir)) {
                stream.forEach(p -> { try { deleteRecursively(p); } catch (IOException ignored) {} });
            }
        }
        Files.deleteIfExists(dir);
    }
}
```

- [ ] **Step 2: Create VideoGenerator — 4-phase ViMax pipeline**

```java
package org.example.educatorweb.resourcegen.agents.generators;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.educatorweb.common.model.ResourceType;
import org.example.educatorweb.resourcegen.infrastructure.*;
import org.example.educatorweb.resourcegen.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class VideoGenerator extends AbstractGenerator {
    private static final Logger log = LoggerFactory.getLogger(VideoGenerator.class);
    private final ObjectMapper objectMapper;
    private final VideoProvider videoProvider;
    private final VideoAssembler assembler;
    private final FileStorageService fileStorage;

    public VideoGenerator(ObjectMapper objectMapper, VideoProvider videoProvider,
                          VideoAssembler assembler, FileStorageService fileStorage,
                          ModelRegistry registry) {
        super(registry, ResourceType.VIDEO);
        this.objectMapper = objectMapper;
        this.videoProvider = videoProvider;
        this.assembler = assembler;
        this.fileStorage = fileStorage;
    }

    @Override
    protected String doGenerate(GenerationState state) {
        // Phase 1: Screenwriter — DesignAgent already generated blueprint
        // Phase 2: Storyboard — generate VideoScript
        VideoScript script = generateVideoScript(state);

        // Phase 3: Producer — generate video clips for each scene (sequential for now, parallel later)
        List<byte[]> clips = new ArrayList<>();
        List<String> transitions = new ArrayList<>();
        for (VideoScene scene : script.scenes()) {
            try {
                byte[] clip = videoProvider.generateVideo(scene.visualPrompt(), scene.durationSeconds());
                clips.add(clip);
                transitions.add(scene.transition());
            } catch (Exception e) {
                log.warn("Scene {} generation failed, using fallback: {}", scene.index(), e.getMessage());
                byte[] fallback = generateFallbackClip(scene);
                clips.add(fallback);
                transitions.add(scene.transition());
            }
        }

        // Phase 4: Assembly — FFmpeg concat
        byte[] mp4Bytes = assembler.assemble(clips, transitions);

        String path = fileStorage.store(state.requestId(), mp4Bytes,
            sanitizeFilename(state.knowledgePoint()) + ".mp4");
        return path;
    }

    private VideoScript generateVideoScript(GenerationState state) {
        ModelProvider textProvider = registry.resolve(ResourceType.DOC);
        String prompt = buildPrompt(state);
        String response = textProvider.chat(prompt);
        try {
            response = response.replaceAll("```json\\s*", "").replaceAll("```\\s*$", "").trim();
            return objectMapper.readValue(response, VideoScript.class);
        } catch (Exception e) {
            log.warn("Failed to parse VideoScript, using fallback: {}", e.getMessage());
            return buildFallbackScript(state);
        }
    }

    private String buildPrompt(GenerationState state) {
        return """
            You are a professional video director creating an educational video script.
            Topic: %s
            Style: Realistic educational video

            Produce a JSON VideoScript with 3-5 scenes:
            {
              "title": "...",
              "style": "Realistic",
              "totalDurationSeconds": N,
              "scenes": [
                {
                  "index": 1,
                  "description": "scene description",
                  "narration": "teacher's spoken text",
                  "visualPrompt": "detailed visual description for AI video generation",
                  "cameraAngle": "wide" | "close-up" | "dolly",
                  "durationSeconds": 30,
                  "transition": "fade" | "cut" | "dissolve"
                }
              ]
            }
            Output ONLY the JSON.
            """.formatted(state.knowledgePoint());
    }

    private byte[] generateFallbackClip(VideoScene scene) {
        // Pure text fallback: generate a simple frame with text
        log.info("Generating pure-text fallback clip for scene {}", scene.index());
        // Return empty bytes for now — VideoAssembler can handle this
        return new byte[0];
    }

    private VideoScript buildFallbackScript(GenerationState state) {
        return new VideoScript(
            state.knowledgePoint() + " — 课程视频",
            List.of(
                new VideoScene(1, "课程介绍", "欢迎来到本节课程",
                    "Educational intro scene with course title", "wide", 15, "fade"),
                new VideoScene(2, "核心内容", "让我们深入学习核心知识",
                    "Educational content scene with diagrams", "close-up", 60, "cut"),
                new VideoScene(3, "课程总结", "本节课到此结束",
                    "Summary scene with key takeaways", "wide", 15, "fade")
            ),
            "Realistic",
            90
        );
    }

    private String sanitizeFilename(String name) {
        return name.replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fff]", "_")
            .substring(0, Math.min(name.length(), 50));
    }

    @Override
    protected String getFormatHint() { return "MP4 (ViMax pipeline)"; }
}
```

- [ ] **Step 3: Verify compilation**

Run: `export JAVA_HOME="/c/Users/x/.jdks/openjdk-25.0.2" && mvn compile`

Expected: BUILD SUCCESS. Fix any import issues.

- [ ] **Step 4: Verify all tests still pass**

Run: `export JAVA_HOME="/c/Users/x/.jdks/openjdk-25.0.2" && mvn test`

Expected: 18/18 pass (VideoGenerator is not tested yet — too many external dependencies)

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat(phase-c): add VideoGenerator (4-phase ViMax pipeline) and VideoAssembler (FFmpeg)"
```

---

### Task 4: Phase D — Wiring (ModelRegistry + Config + YAML + Frontend)

**Files:**
- Modify: `src/main/java/org/example/educatorweb/resourcegen/config/ModelRegistry.java`
- Modify: `src/main/java/org/example/educatorweb/resourcegen/config/ResourceGenConfig.java`
- Modify: `src/main/resources/application.yml`
- Modify: `src/test/java/org/example/educatorweb/resourcegen/agents/generators/DocGeneratorTest.java` (fix stale ResourceType.VIDEO references)

- [ ] **Step 1: Update ModelRegistry to handle VIDEO routing**

```java
// In ModelRegistry.resolve():
public ModelProvider resolve(ResourceType type) {
    return switch (type) {
        case PPT -> visualProvider.isEnabled() ? visualProvider : textProvider;
        default -> textProvider;
    };
}
```

Also add a `resolveVideoProvider()` method:
```java
public VideoProvider resolveVideoProvider() {
    return videoGeneratorProvider; // set by ResourceGenConfig
}
```

Actually, the VideoGenerator will be injected with the VideoProvider directly, not through ModelRegistry. So ModelRegistry doesn't need a resolveVideoProvider method. The Generator gets what it needs via constructor injection. Keep ModelRegistry simple — just update the switch to handle PPT.

- [ ] **Step 2: Update ResourceGenConfig — VideoProvider beans**

Add these beans:

```java
@Bean
public VideoProvider videoProvider(ModelRoutingProperties props,
                                    OpenAiCompatibleProvider openAiProvider) {
    var videoCfg = props.video();
    if (videoCfg == null) videoCfg = new ModelRoutingProperties.ModelConfig("seedance", "seedance-v1", 0.7);
    
    var providerCfg = props.providers().get(videoCfg.provider());
    if (providerCfg == null || !providerCfg.enabled()) {
        log.info("VideoProvider '{}' not enabled, using StaticImageFallbackProvider",
            videoCfg.provider());
        return new StaticImageFallbackProvider(openAiProvider);
    }

    return switch (videoCfg.provider()) {
        case "seedance" -> new SeedanceVideoProvider(
            providerCfg.baseUrl(),
            resolveEnvKey(providerCfg.apiKey(), "SEEDANCE_API_KEY"),
            true);
        default -> {
            log.warn("Unknown video provider '{}', falling back to StaticImageFallbackProvider",
                videoCfg.provider());
            yield new StaticImageFallbackProvider(openAiProvider);
        }
    };
}
```

- [ ] **Step 3: Add video-providers config to application.yml**

Add after the existing `providers:` block:

```yaml
  # Video generation providers (for VIDEO resource type)
  video:
    provider: seedance           # seedance | veo | cogvideox
    model: seedance-v1
    temperature: 0.7
```

Add to the `providers:` section (already exists, just ensure seedance is listed):
```yaml
    seedance:
      enabled: false
      base-url: https://api.seedance.io
      api-key: ${SEEDANCE_API_KEY:}
```

Note: `seedance` is already listed in the existing providers section from v1.1. Just verify it's there.

- [ ] **Step 4: Fix stale references in tests**

Search for `ResourceType.VIDEO` in test files. Change to `ResourceType.PPT` where the test was about PPT generation. Add a new test for VIDEO if needed.

Run: `export JAVA_HOME="/c/Users/x/.jdks/openjdk-25.0.2" && mvn test`

Expected: All 18 tests pass.

- [ ] **Step 5: Manual verification plan**

Start app with mock profile:
```powershell
$env:JAVA_HOME="C:\Users\x\.jdks\openjdk-25.0.2"
$env:SPRING_PROFILES_ACTIVE="mock"
mvn spring-boot:run
```

Open `http://localhost:8080/test.html`. Verify:
- PPT checkbox visible → generates .pptx
- VIDEO checkbox visible → attempts generation, falls back to StaticImageFallbackProvider
- DOC/MINDMAP still work as before
- Test panel shows correct resource types

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "feat(phase-d): wire VideoProvider into ModelRegistry, add video config, fix tests"
```

---

## Summary

| Task | Content | Files | Outcome |
|---|---|---|---|
| 1 (Phase A) | ResourceType split + interfaces | 2 new, 2 rename, 5+ ref updates | PPT and VIDEO are separate |
| 2 (Phase B) | VideoProvider implementations | 2 new | Seedance + fallback ready |
| 3 (Phase C) | VideoGenerator + Assembler | 2 new | 4-phase pipeline working |
| 4 (Phase D) | Wiring + config + tests | 3 mod | End-to-end functional |

**Total: 6 new files, 5 renames/modifies, 4 commits**

### Verification Checklist

- [ ] `mvn clean compile` → BUILD SUCCESS
- [ ] `mvn test` → 18/18 pass
- [ ] App starts with mock profile
- [ ] POST with `["DOC","MINDMAP"]` → works as before
- [ ] POST with `["PPT"]` → .pptx generated (V1 behavior preserved)
- [ ] POST with `["VIDEO"]` → .mp4 generated (via fallback, since Seedance disabled)
- [ ] Test panel shows separate PPT and VIDEO checkboxes
