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
public class PptGenerator extends AbstractGenerator {

    private final PptxBuilder pptxBuilder;
    private final FileStorageService fileStorageService;
    private final ObjectMapper objectMapper;

    public PptGenerator(ModelRegistry registry, PptxBuilder pptxBuilder,
                          FileStorageService fileStorageService) {
        super(registry, ResourceType.PPT);
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
        ModelProvider visualProvider = registry.resolve(supportedType()); // PPT -> visual
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
        log.info("PptGenerator: sending LectureScript prompt to text model (prompt length={})",
            prompt.length());

        String response = provider.chat(prompt);
        log.info("PptGenerator: received LectureScript response (length={})",
            response != null ? response.length() : 0);

        if (response == null || response.isBlank()) {
            log.warn("PptGenerator: empty response, using fallback script");
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
            sb.append("- 知识基础: ").append(profile.getKnowledgeBaseLevel()).append("\n");
            sb.append("- 认知风格: ").append(profile.getCognitiveStyleType()).append("\n");
            if (profile.getErrorPatternTags() != null
                    && !profile.getErrorPatternTags().isEmpty()) {
                sb.append("- 常见错误模式: ").append(profile.getErrorPatternTags()).append("\n");
            }
            sb.append("- 学习节奏: ").append(profile.getLearningPaceType()).append("\n");
            sb.append("- 内容偏好: ").append(profile.getContentPreferenceType()).append("\n");
            sb.append("- 学习目标: ").append(profile.getGoalOrientationType()).append("\n");
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
        // Philosophy: Slides are VISUAL AIDS, not lecture scripts.
        // Bullet points must be keywords / short scannable phrases.
        // Full explanations and spoken language belong in narration (speaker notes).
        sb.append("\n## 幻灯片设计理念\n\n");
        sb.append("**【核心原则】幻灯片是视觉辅助工具，不是讲义文稿。**\n\n");
        sb.append("幻灯片上应该放的内容：\n");
        sb.append("- 关键词、短语、要点列表（一眼就能扫读完）\n");
        sb.append("- 数据、图表标注、公式\n");
        sb.append("- 简洁的定义、对比表格\n\n");
        sb.append("不应该放在幻灯片上的内容（这些放入 narration/备注）：\n");
        sb.append("- 完整的长句子和口语化表述\n");
        sb.append("- 讲师的过渡语（如「接下来我们看看...」）\n");
        sb.append("- 以讲师身份署名或说话的内容\n");
        sb.append("- 长篇解释段落\n\n");
        sb.append("**判断标准**：如果一段文字读起来像老师「说」的而不是「展示」的，它就属于备注栏。\n");
        sb.append("每条 bullet point 控制在 30 字以内（中文）或 20 词以内（英文）。\n\n");

        sb.append("## 输出要求\n\n");
        sb.append("请设计一套结构完整的课程幻灯片，输出为 JSON 格式。\n\n");
        sb.append("要求：\n");
        sb.append("1. 共设计 6-10 张幻灯片，覆盖知识点的核心方面\n");
        sb.append("2. 每张幻灯片：\n");
        sb.append("   - title: 明确的幻灯片标题（如「SVM 核心思想：最大间隔分类器」）\n");
        sb.append("   - bulletPoints: 4-6 个精炼要点，每条≤30字\n");
        sb.append("     · 需要对比的内容用「A vs B」格式：如「硬间隔：严格线性可分 / 软间隔：允许少量误分类」\n");
        sb.append("     · 需要列举的内容用「→」串联因果：如「欠拟合 → 高偏差 → 模型过于简单 → 增加特征」\n");
        sb.append("     · 数学概念用简洁公式标注：如「决策函数：f(x) = sign(w·x + b)」\n");
        sb.append("   - narration: 讲师详细讲解词（口语化，150-300字），\n");
        sb.append("     包含引入、展开、举例、过渡。这是你真正「讲」的内容，会放入演讲者备注\n");
        sb.append("   - visualPrompt: 英文，描述该页幻灯片应该配什么图/表/架构图\n");
        sb.append("     （如 'A 2x2 comparison table showing hard margin vs soft margin SVM with bias-variance tradeoff'）\n");
        sb.append("   - durationSeconds: 本页讲解时长（60-120秒）\n");
        sb.append("3. 幻灯片结构递进：\n");
        sb.append("   背景动机 → 核心概念 → 数学原理 → 算法流程 → 实例对比 → 应用场景 → 总结要点\n");
        sb.append("4. 多样性要求：不同类型的幻灯片应有不同的结构\n");
        sb.append("   - 概念页：定义 + 关键特征（bullet points）+ 直观类比\n");
        sb.append("   - 原理页：公式 + 推导要点 + 几何/物理直觉\n");
        sb.append("   - 对比页：左/右或表格对比 + 各自优缺点 + 适用场景\n");
        sb.append("   - 案例页：具体数据 + 分析步骤 + 结论\n");
        sb.append("   - 总结页：核心要点回顾 + 知识关联 + 下一步学习方向\n");
        sb.append("5. 包含课程标题（title）、讲师姓名（teacherName）和总时长（estimatedDurationSeconds）\n\n");

        sb.append("输出 JSON 格式：\n");
        sb.append("{\n");
        sb.append("  \"title\": \"知识点名称 — 课程精讲\",\n");
        sb.append("  \"teacherName\": \"讲师姓名\",\n");
        sb.append("  \"estimatedDurationSeconds\": 900,\n");
        sb.append("  \"slides\": [\n");
        sb.append("    {\n");
        sb.append("      \"index\": 1,\n");
        sb.append("      \"title\": \"引言：为什么需要 [知识点]？\",\n");
        sb.append("      \"bulletPoints\": [\n");
        sb.append("        \"核心问题：[一句话描述要解决什么问题]\",\n");
        sb.append("        \"传统方法局限：[旧方法的关键缺陷]\",\n");
        sb.append("        \"本课程路线图：概念 → 原理 → 实践\"\n");
        sb.append("      ],\n");
        sb.append("      \"narration\": \"今天我们来讲...（150-300字口语化讲解）\",\n");
        sb.append("      \"visualPrompt\": \"A diagram showing the problem motivation with a real-world example illustration\",\n");
        sb.append("      \"durationSeconds\": 90\n");
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
