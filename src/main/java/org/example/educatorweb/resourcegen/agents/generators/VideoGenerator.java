package org.example.educatorweb.resourcegen.agents.generators;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.educatorweb.common.model.ResourceType;
import org.example.educatorweb.knowledgegraph.model.KnowledgeContext;
import org.example.educatorweb.profile.model.StudentProfile;
import org.example.educatorweb.rag.model.DocumentSnippet;
import org.example.educatorweb.resourcegen.config.ModelRegistry;
import org.example.educatorweb.resourcegen.infrastructure.FileStorageService;
import org.example.educatorweb.resourcegen.infrastructure.ModelProvider;
import org.example.educatorweb.resourcegen.infrastructure.PptxBuilder;
import org.example.educatorweb.resourcegen.model.GenerationState;
import org.example.educatorweb.resourcegen.model.LectureScript;
import org.example.educatorweb.resourcegen.model.ResourceBlueprint;
import org.example.educatorweb.resourcegen.model.SlideScript;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class VideoGenerator extends AbstractGenerator {

    private final PptxBuilder pptxBuilder;
    private final FileStorageService fileStorageService;
    private final ObjectMapper objectMapper;

    public VideoGenerator(ModelRegistry registry, PptxBuilder pptxBuilder,
                          FileStorageService fileStorageService) {
        super(registry, ResourceType.VIDEO);
        this.pptxBuilder = pptxBuilder;
        this.fileStorageService = fileStorageService;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    protected String doGenerate(GenerationState state) {
        // Phase 1: Text model generates LectureScript
        ModelProvider textProvider = registry.resolve(ResourceType.DOC); // text model
        LectureScript script = generateLectureScript(textProvider, state);

        // Phase 2: Visual model generates PPTX (or fallback to Apache POI)
        ModelProvider visualProvider = registry.resolve(supportedType()); // VIDEO -> visual
        byte[] pptxBytes;
        if (visualProvider.isEnabled() && !"deepseek".equals(visualProvider.providerName())) {
            try {
                pptxBytes = generateVisualPptx(visualProvider, script);
            } catch (Exception e) {
                log.warn("Visual PPTX generation failed, falling back to Apache POI: {}", e.getMessage());
                pptxBytes = buildFallbackPptx(script);
            }
        } else {
            log.info("Visual provider not enabled or is deepseek — using Apache POI fallback");
            pptxBytes = buildFallbackPptx(script);
        }

        String topicTitle = state.blueprint() != null && state.blueprint().title() != null
            ? state.blueprint().title()
            : state.knowledgePoint();
        String path = fileStorageService.store(state.requestId(), pptxBytes,
            sanitizeFilename(topicTitle) + ".pptx");
        return path;
    }

    @Override
    protected String getFormatHint() {
        return "PPTX (two-phase: LectureScript + Apache POI)";
    }

    // ---- Phase 1: Text model generates LectureScript ----

    private LectureScript generateLectureScript(ModelProvider provider, GenerationState state) {
        String prompt = buildLectureScriptPrompt(state);
        log.info("VideoGenerator: sending LectureScript prompt to text model (prompt length={})",
            prompt.length());

        String response = provider.chat(prompt);
        log.info("VideoGenerator: received LectureScript response (length={})",
            response != null ? response.length() : 0);

        if (response == null || response.isBlank()) {
            log.warn("VideoGenerator: empty response, using fallback script");
            return buildFallbackScript(state);
        }

        try {
            // Strip code fences
            response = response.replaceAll("```json\\s*", "").replaceAll("```\\s*$", "").trim();
            return objectMapper.readValue(response, LectureScript.class);
        } catch (Exception e) {
            log.warn("Failed to parse LectureScript, using fallback: {}", e.getMessage());
            return buildFallbackScript(state);
        }
    }

    // ---- Phase 2: Visual model generates PPTX ----

    private byte[] generateVisualPptx(ModelProvider provider, LectureScript script) {
        // Build a prompt describing the full PPT layout based on script
        StringBuilder sb = new StringBuilder();
        sb.append("Create a presentation with the following slides:\n\n");
        for (SlideScript s : script.slides()) {
            sb.append("Slide ").append(s.index()).append(": ").append(s.title()).append("\n");
            sb.append("Visual: ").append(s.visualPrompt()).append("\n");
            sb.append("Bullets: ").append(String.join(", ", s.bulletPoints())).append("\n\n");
        }
        String response = provider.chat(sb.toString());
        // For now, visual provider output is text — fallback to POI
        // Future: parse provider-specific PPTX/HTML format
        throw new UnsupportedOperationException(
            "Visual provider PPTX parsing not yet implemented — will fallback to Apache POI");
    }

    // ---- Fallback: Apache POI-based PPTX from LectureScript ----

    private byte[] buildFallbackPptx(LectureScript script) {
        List<PptxBuilder.SlideData> slides = script.slides().stream()
            .map(s -> new PptxBuilder.SlideData(s.title(), s.bulletPoints(), s.narration()))
            .toList();
        return pptxBuilder.buildPresentation(script.title(), slides);
    }

    // ---- Fallback: hardcoded LectureScript when LLM fails ----

    private LectureScript buildFallbackScript(GenerationState state) {
        String topic = state.knowledgePoint();
        return new LectureScript(
            topic + " — 课程讲解",
            List.of(
                new SlideScript(1, "概述",
                    List.of(topic, "学习目标", "内容结构"),
                    "欢迎来到本节课程",
                    "Title slide with course overview illustration",
                    30),
                new SlideScript(2, "核心概念",
                    List.of("关键概念", "基本原理", "数学推导"),
                    "让我们深入理解核心原理",
                    "Diagram showing concept relationships and key formula",
                    60),
                new SlideScript(3, "实践应用",
                    List.of("典型案例", "常见问题", "解决方案"),
                    "通过实例加深理解",
                    "Example scenarios with step-by-step visualization",
                    60),
                new SlideScript(4, "总结",
                    List.of("要点回顾", "知识关联", "下一步学习"),
                    "本节课到此结束",
                    "Summary mindmap connecting all key points",
                    30)
            ),
            "李老师",
            180
        );
    }

    // ---- Prompt builder: asks for LectureScript JSON format ----

    private String buildLectureScriptPrompt(GenerationState state) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一位资深的教学设计师，擅长为知识点设计结构清晰的课程讲稿。\n\n");

        // --- Student profile ---
        sb.append("## 学生画像\n");
        StudentProfile profile = state.profile();
        if (profile != null) {
            sb.append("- 知识基础: ").append(profile.knowledgeBase()).append("\n");
            sb.append("- 认知风格: ").append(profile.cognitiveStyle()).append("\n");
            if (profile.errorPattern() != null && profile.errorPattern().tags() != null
                    && !profile.errorPattern().tags().isEmpty()) {
                sb.append("- 常见错误模式: ").append(profile.errorPattern().tags()).append("\n");
            }
            sb.append("- 学习节奏: ").append(profile.learningPace()).append("\n");
            sb.append("- 内容偏好: ").append(profile.contentPreference()).append("\n");
            sb.append("- 学习目标: ").append(profile.goalOrientation()).append("\n");
        } else {
            sb.append("（无可用画像数据，请使用通用风格和中等难度）\n");
        }

        // --- Knowledge graph context ---
        sb.append("\n## 知识点背景\n");
        KnowledgeContext kg = state.knowledgeContext();
        if (kg != null) {
            if (!kg.prerequisites().isEmpty()) {
                sb.append("- 前置知识: ").append(String.join(", ", kg.prerequisites())).append("\n");
            }
            if (!kg.successors().isEmpty()) {
                sb.append("- 后续知识: ").append(String.join(", ", kg.successors())).append("\n");
            }
            if (!kg.relatedConcepts().isEmpty()) {
                sb.append("- 相关概念: ").append(String.join(", ", kg.relatedConcepts())).append("\n");
            }
            sb.append("- 难度级别: ").append(kg.difficultyLevel()).append(" / 5\n");
        } else {
            sb.append("（无可用图谱数据）\n");
        }

        // --- RAG context ---
        sb.append("\n## 参考资料\n");
        List<DocumentSnippet> ragContexts = state.ragContext();
        if (ragContexts != null && !ragContexts.isEmpty()) {
            sb.append("以下是相关的参考材料，请在内容中适当引用和拓展：\n\n");
            for (int i = 0; i < ragContexts.size(); i++) {
                DocumentSnippet snippet = ragContexts.get(i);
                sb.append(i + 1).append(". [来源: ").append(snippet.source()).append("] ")
                  .append(snippet.content()).append("\n");
            }
        } else {
            sb.append("（无可用参考资料）\n");
        }

        // --- Blueprint sections ---
        sb.append("\n## 内容大纲\n");
        ResourceBlueprint blueprint = state.blueprint();
        if (blueprint != null && blueprint.sections() != null && !blueprint.sections().isEmpty()) {
            sb.append("请围绕以下大纲设计幻灯片：\n\n");
            for (ResourceBlueprint.BlueprintSection section : blueprint.sections()) {
                sb.append("- ").append(section.heading()).append(": ").append(section.keyPoints()).append("\n");
            }
        } else {
            sb.append("请围绕知识点「").append(state.knowledgePoint()).append("」自行设计幻灯片结构。\n");
        }

        // --- Knowledge point ---
        sb.append("\n## 目标知识点\n");
        sb.append(state.knowledgePoint()).append("\n");

        // --- Output requirements: LectureScript JSON format ---
        sb.append("\n## 输出要求\n\n");
        sb.append("请设计一套完整的课程讲稿（LectureScript），输出为 JSON 格式。\n\n");
        sb.append("要求：\n");
        sb.append("1. 共设计 5-10 张幻灯片（根据知识点复杂度调整）\n");
        sb.append("2. 每张幻灯片包含：\n");
        sb.append("   - index: 幻灯片序号（从1开始）\n");
        sb.append("   - title: 幻灯片标题\n");
        sb.append("   - bulletPoints: 要点列表（每条不超过30字）\n");
        sb.append("   - narration: 讲师讲解词（口语化，便于讲授）\n");
        sb.append("   - visualPrompt: 视觉设计描述（用于AI生成幻灯片配图）\n");
        sb.append("   - durationSeconds: 本页建议讲解时长（秒）\n");
        sb.append("3. 幻灯片结构应逻辑递进：引入 → 概念讲解 → 原理分析 → 案例演示 → 总结\n");
        sb.append("4. 包含课程标题（title）、讲师姓名（teacherName）和总时长（estimatedDurationSeconds）\n\n");

        sb.append("输出 JSON 格式：\n");
        sb.append("{\n");
        sb.append("  \"title\": \"课程标题\",\n");
        sb.append("  \"teacherName\": \"讲师姓名\",\n");
        sb.append("  \"estimatedDurationSeconds\": 600,\n");
        sb.append("  \"slides\": [\n");
        sb.append("    {\n");
        sb.append("      \"index\": 1,\n");
        sb.append("      \"title\": \"幻灯片标题\",\n");
        sb.append("      \"bulletPoints\": [\"要点1\", \"要点2\", \"要点3\"],\n");
        sb.append("      \"narration\": \"讲师的讲解词...\",\n");
        sb.append("      \"visualPrompt\": \"视觉设计描述...\",\n");
        sb.append("      \"durationSeconds\": 60\n");
        sb.append("    }\n");
        sb.append("  ]\n");
        sb.append("}\n\n");

        sb.append("请直接输出 JSON，不要包含解释性文字和代码块标记。");

        return sb.toString();
    }

    private String sanitizeFilename(String name) {
        return name.replaceAll("[\\\\/:*?\"<>|]", "_").replaceAll("\\s+", "_");
    }
}
