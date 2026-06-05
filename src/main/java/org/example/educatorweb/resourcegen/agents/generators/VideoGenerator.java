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
        // Phase 1: DeepSeek generates VideoScript (slide content + narration)
        VideoScript script = generateVideoScript(state);

        // Phase 2: Seedream generates one image per slide (NOT Seedance video — too expensive and poor quality for education)
        List<byte[]> slideImages = new ArrayList<>();
        for (VideoScene scene : script.scenes()) {
            try {
                // Use image generation with an educational-diagram focused prompt
                String imagePrompt = buildEducationalImagePrompt(scene, state.knowledgePoint());
                byte[] image = videoProvider.generateImage(imagePrompt);
                slideImages.add(image);
                log.info("Slide {}: generated image ({} bytes)", scene.index(), image.length);
            } catch (Exception e) {
                log.warn("Slide {} image generation failed: {}", scene.index(), e.getMessage());
                slideImages.add(new byte[0]);
            }
        }

        // Filter out empty images
        List<byte[]> validImages = slideImages.stream()
            .filter(img -> img.length > 0)
            .toList();

        if (validImages.isEmpty()) {
            log.warn("No valid slide images generated — returning placeholder");
            String path = fileStorage.store(state.requestId(), new byte[0],
                sanitizeFilename(state.knowledgePoint()) + ".mp4");
            return path;
        }

        // Phase 3: FFmpeg images → video (each image shown for its durationSeconds)
        byte[] mp4Bytes = assembler.imagesToVideo(validImages, script.scenes());

        String path = fileStorage.store(state.requestId(), mp4Bytes,
            sanitizeFilename(state.knowledgePoint()) + ".mp4");
        log.info("VideoGenerator: produced {} bytes for {}", mp4Bytes.length, path);
        return path;
    }

    /**
     * Build a prompt optimized for educational slide images.
     * Seedream is good at generating clean diagrams, text layouts, and illustrations.
     */
    private String buildEducationalImagePrompt(VideoScene scene, String topic) {
        // Use the visualPrompt directly if DeepSeek provided a good one,
        // otherwise fall back to constructing from scene data.
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

    private VideoScript generateVideoScript(GenerationState state) {
        ModelProvider textProvider = registry.resolve(ResourceType.DOC);
        String prompt = buildPrompt(state);
        String response = textProvider.chat(prompt);
        try {
            response = response.replaceAll("```json\\s*", "")
                               .replaceAll("```\\s*$", "").trim();
            return objectMapper.readValue(response, VideoScript.class);
        } catch (Exception e) {
            log.warn("Failed to parse VideoScript, using fallback: {}", e.getMessage());
            return buildFallbackScript(state);
        }
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
                new VideoScene(3, "数学推导",
                    "推导过程如下", "Mathematical derivation", "wide", 30, "cut"),
                new VideoScene(4, "总结",
                    "本节课要点回顾", "Summary with key takeaways", "wide", 10, "fade")
            ),
            "Realistic", 80
        );
    }

    private String sanitizeFilename(String name) {
        return name.replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fff]", "_")
            .substring(0, Math.min(name.length(), 50));
    }

    @Override
    protected String getFormatHint() { return "MP4 (slide images + narration script)"; }
}
