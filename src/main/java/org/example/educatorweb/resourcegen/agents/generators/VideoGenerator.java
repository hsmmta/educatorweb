package org.example.educatorweb.resourcegen.agents.generators;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.educatorweb.common.model.ResourceType;
import org.example.educatorweb.resourcegen.config.ModelRegistry;
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
        // Phase 2: Storyboard — DeepSeek generates VideoScript
        VideoScript script = generateVideoScript(state);

        // Phase 3: Producer — generate clips for each scene
        List<byte[]> clips = new ArrayList<>();
        List<String> transitions = new ArrayList<>();
        for (VideoScene scene : script.scenes()) {
            try {
                byte[] clip = videoProvider.generateVideo(
                    scene.visualPrompt(), scene.durationSeconds());
                clips.add(clip);
            } catch (Exception e) {
                log.warn("Scene {} video generation failed: {}. "
                    + "Using empty fallback (VideoAssembler will skip).",
                    scene.index(), e.getMessage());
                clips.add(new byte[0]); // empty → VideoAssembler skips
            }
            transitions.add(scene.transition());
        }

        // Phase 4: Assembly — FFmpeg concat
        byte[] mp4Bytes = assembler.assemble(clips, transitions);

        String filename = sanitizeFilename(state.knowledgePoint());
        if (mp4Bytes.length == 0) {
            log.warn("VideoGenerator: all scenes fell back, returning placeholder path");
        }
        String path = fileStorage.store(state.requestId(), mp4Bytes, filename + ".mp4");
        return path;
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
            You are a professional video director creating an educational video.
            Topic: %s
            Style: Realistic educational video with clear visual explanations

            Produce a JSON VideoScript with 3-5 scenes:
            {
              "title": "lesson title",
              "style": "Realistic",
              "totalDurationSeconds": 90,
              "scenes": [
                {
                  "index": 1,
                  "description": "what happens in this scene",
                  "narration": "teacher's spoken words for this scene",
                  "visualPrompt": "detailed English visual description for AI video generation",
                  "cameraAngle": "wide",
                  "durationSeconds": 30,
                  "transition": "fade"
                }
              ]
            }
            Output ONLY the JSON, no markdown fences.
            """.formatted(state.knowledgePoint());
    }

    private VideoScript buildFallbackScript(GenerationState state) {
        return new VideoScript(
            state.knowledgePoint(),
            List.of(
                new VideoScene(1, "课程介绍",
                    "欢迎来到本节课程，我们将学习" + state.knowledgePoint(),
                    "Educational intro scene with course title card",
                    "wide", 15, "fade"),
                new VideoScene(2, "核心内容",
                    "让我们深入学习核心知识",
                    "Educational content scene with animated diagrams",
                    "close-up", 60, "cut"),
                new VideoScene(3, "课程总结",
                    "本节课到此结束，请回顾今天的要点",
                    "Summary scene with key takeaways text overlay",
                    "wide", 15, "fade")
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
    protected String getFormatHint() { return "MP4 (ViMax 4-phase pipeline)"; }
}
