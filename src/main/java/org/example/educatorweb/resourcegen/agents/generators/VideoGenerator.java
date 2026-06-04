package org.example.educatorweb.resourcegen.agents.generators;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.educatorweb.common.model.ResourceType;
import org.example.educatorweb.knowledgegraph.model.KnowledgeContext;
import org.example.educatorweb.profile.model.StudentProfile;
import org.example.educatorweb.rag.model.DocumentSnippet;
import org.example.educatorweb.resourcegen.infrastructure.FileStorageService;
import org.example.educatorweb.resourcegen.infrastructure.PptxBuilder;
import org.example.educatorweb.resourcegen.model.GenerationState;
import org.example.educatorweb.resourcegen.model.ResourceBlueprint;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class VideoGenerator extends AbstractGenerator {

    private final PptxBuilder pptxBuilder;
    private final FileStorageService fileStorageService;
    private final ObjectMapper objectMapper;

    public VideoGenerator(ChatClient chatClient, PptxBuilder pptxBuilder,
                          FileStorageService fileStorageService) {
        super(chatClient, ResourceType.VIDEO);
        this.pptxBuilder = pptxBuilder;
        this.fileStorageService = fileStorageService;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    protected String doGenerate(GenerationState state) {
        // Step 1: LLM generates slide outline as JSON array
        String jsonStr = generateSlideOutline(state);
        log.info("VideoGenerator: received LLM response (length={})",
            jsonStr != null ? jsonStr.length() : 0);

        // Step 2: Parse slides, build PPTX via PptxBuilder
        List<PptxBuilder.SlideData> slides;
        try {
            slides = parseSlides(jsonStr);
        } catch (Exception e) {
            log.warn("VideoGenerator: JSON parse failed, using fallback slides", e);
            slides = buildFallbackSlides(state);
        }

        // Step 3: Build PPTX
        String topicTitle = state.blueprint() != null && state.blueprint().title() != null
            ? state.blueprint().title()
            : state.knowledgePoint();
        byte[] pptxBytes = pptxBuilder.buildPresentation(topicTitle, slides);

        // Step 4: Store file via FileStorageService
        String filename = sanitizeFilename(topicTitle) + ".pptx";
        String filePath = fileStorageService.store(state.requestId(), pptxBytes, filename);

        log.info("VideoGenerator: PPTX stored at {}", filePath);
        return filePath;
    }

    @Override
    protected String getFormatHint() {
        return "PPTX (slide outline JSON)";
    }

    private String generateSlideOutline(GenerationState state) {
        String prompt = buildPrompt(state);
        log.info("VideoGenerator: sending prompt to LLM for topic={} (prompt length={})",
            state.knowledgePoint(), prompt.length());

        String response = chatClient.prompt().user(prompt).call().content();
        return response != null ? response : "[]";
    }

    private List<PptxBuilder.SlideData> parseSlides(String jsonStr) throws Exception {
        // Strip code fences if present
        String cleaned = jsonStr.trim();
        if (cleaned.startsWith("```json")) {
            cleaned = cleaned.substring(7);
        } else if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring(3);
        }
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - 3);
        }
        cleaned = cleaned.trim();

        List<PptxBuilder.SlideData> slides = objectMapper.readValue(cleaned,
            new TypeReference<List<PptxBuilder.SlideData>>() {});

        if (slides == null || slides.isEmpty()) {
            throw new IllegalStateException("Parsed slide list is empty");
        }
        return slides;
    }

    private List<PptxBuilder.SlideData> buildFallbackSlides(GenerationState state) {
        String topic = state.knowledgePoint();
        List<PptxBuilder.SlideData> fallback = new ArrayList<>();
        fallback.add(new PptxBuilder.SlideData("概述",
            List.of(topic + " 的基本概念与背景介绍",
                "本课程的学习目标与预期成果",
                "内容结构与学习路径说明"),
            "开场引入，建立学习框架"));
        fallback.add(new PptxBuilder.SlideData("核心原理",
            List.of(topic + " 的核心机制与工作原理",
                "关键概念与术语解析",
                "典型应用场景与实践案例"),
            "深入讲解核心知识点"));
        fallback.add(new PptxBuilder.SlideData("总结",
            List.of("本章知识要点回顾",
                "与其他知识点的关联与拓展",
                "课后思考与练习建议"),
            "总结回顾，引导后续学习"));
        return fallback;
    }

    private String buildPrompt(GenerationState state) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一位资深的教学设计师，擅长为知识点设计结构清晰的PPT演示文稿大纲。\n\n");

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

        // --- Output requirements ---
        sb.append("\n## 输出要求\n\n");
        sb.append("请设计一套完整的PPT演示文稿大纲，输出为 JSON 数组格式。\n\n");
        sb.append("要求：\n");
        sb.append("1. 共设计 5-10 张幻灯片（根据知识点复杂度调整）\n");
        sb.append("2. 每张幻灯片包含标题（title）、要点列表（bullets）和讲师备注（notes）\n");
        sb.append("3. 幻灯片结构应逻辑递进：引入 → 概念讲解 → 原理分析 → 案例演示 → 总结\n");
        sb.append("4. 要点简洁明了，每条不超过30字\n");
        sb.append("5. 备注中包含讲师讲课提示、补充说明或互动建议\n\n");

        sb.append("输出 JSON 格式：\n");
        sb.append("```json\n");
        sb.append("[\n");
        sb.append("  {\n");
        sb.append("    \"title\": \"幻灯片标题\",\n");
        sb.append("    \"bullets\": [\"要点1\", \"要点2\", \"要点3\"],\n");
        sb.append("    \"notes\": \"讲师备注（讲课提示、补充说明等）\"\n");
        sb.append("  }\n");
        sb.append("]\n");
        sb.append("```\n\n");

        sb.append("请直接输出 JSON 数组，不要包含解释性文字。");

        return sb.toString();
    }

    private String sanitizeFilename(String name) {
        return name.replaceAll("[\\\\/:*?\"<>|]", "_").replaceAll("\\s+", "_");
    }
}
