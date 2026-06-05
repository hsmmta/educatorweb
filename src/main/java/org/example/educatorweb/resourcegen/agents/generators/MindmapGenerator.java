package org.example.educatorweb.resourcegen.agents.generators;

import org.example.educatorweb.common.model.ResourceType;
import org.example.educatorweb.knowledgegraph.model.KnowledgeContext;
import org.example.educatorweb.profile.model.StudentProfile;
import org.example.educatorweb.resourcegen.config.ModelRegistry;
import org.example.educatorweb.resourcegen.infrastructure.ModelProvider;
import org.example.educatorweb.resourcegen.model.GenerationState;
import org.example.educatorweb.resourcegen.model.ResourceBlueprint;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class MindmapGenerator extends AbstractGenerator {

    public MindmapGenerator(ModelRegistry registry) {
        super(registry, ResourceType.MINDMAP);
    }

    @Override
    protected String doGenerate(GenerationState state) {
        String prompt = buildPrompt(state);
        log.info("MindmapGenerator: sending prompt to LLM for topic={} (prompt length={})",
            state.knowledgePoint(), prompt.length());

        ModelProvider provider = registry.resolve(supportedType());
        String response = provider.chat(prompt);
        log.info("MindmapGenerator: received LLM response (length={})",
            response != null ? response.length() : 0);

        if (response == null || response.isBlank()) {
            log.warn("MindmapGenerator: empty response from LLM, using fallback");
            return buildFallbackMindmap(state);
        }

        String cleaned = cleanMermaidResponse(response);

        if (!cleaned.stripLeading().startsWith("mindmap")) {
            log.warn("MindmapGenerator: response does not start with 'mindmap', raw start: {}",
                cleaned.substring(0, Math.min(80, cleaned.length())));
            return buildFallbackMindmap(state);
        }

        return cleaned;
    }

    @Override
    protected String getFormatHint() {
        return "Mermaid mindmap";
    }

    private String cleanMermaidResponse(String response) {
        String cleaned = response.trim();

        // Strip markdown code fences
        if (cleaned.startsWith("```mermaid")) {
            cleaned = cleaned.substring(10);
        } else if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring(3);
        }
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - 3);
        }

        return cleaned.trim();
    }

    private String buildFallbackMindmap(GenerationState state) {
        StringBuilder sb = new StringBuilder();
        sb.append("mindmap\n");
        sb.append("  ").append(state.knowledgePoint()).append("\n");

        ResourceBlueprint blueprint = state.blueprint();
        if (blueprint != null && blueprint.sections() != null) {
            for (ResourceBlueprint.BlueprintSection section : blueprint.sections()) {
                appendSectionToMindmap(sb, section, 4);
            }
        } else {
            sb.append("    核心概念\n");
            sb.append("      定义\n");
            sb.append("      原理\n");
            sb.append("    应用场景\n");
        }

        return sb.toString();
    }

    private void appendSectionToMindmap(StringBuilder sb, ResourceBlueprint.BlueprintSection section, int indent) {
        sb.append(" ".repeat(indent)).append(section.heading()).append("\n");
        if (section.children() != null) {
            for (ResourceBlueprint.BlueprintSection child : section.children()) {
                appendSectionToMindmap(sb, child, indent + 2);
            }
        }
    }

    private String buildPrompt(GenerationState state) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一位教育内容可视化专家，擅长将知识点组织成思维导图。\n\n");

        // --- Student profile ---
        sb.append("## 学生画像\n");
        StudentProfile profile = state.profile();
        if (profile != null) {
            sb.append("- 认知风格: ").append(profile.cognitiveStyle()).append("\n");
            sb.append("- 学习节奏: ").append(profile.learningPace()).append("\n");
            sb.append("- 内容偏好: ").append(profile.contentPreference()).append("\n");
        } else {
            sb.append("（无可用画像数据）\n");
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
        } else {
            sb.append("（无可用图谱数据）\n");
        }

        // --- Blueprint sections ---
        sb.append("\n## 内容大纲\n");
        ResourceBlueprint blueprint = state.blueprint();
        if (blueprint != null && blueprint.sections() != null && !blueprint.sections().isEmpty()) {
            sb.append("请以下列大纲为基础构建思维导图：\n\n");
            appendBlueprintSections(sb, blueprint.sections(), 0);
        } else {
            sb.append("请围绕知识点「").append(state.knowledgePoint()).append("」自行构建思维导图结构。\n");
        }

        // --- Knowledge point ---
        sb.append("\n## 目标知识点\n");
        sb.append(state.knowledgePoint()).append("\n");

        // --- Output format ---
        sb.append("\n## 输出要求\n");
        sb.append("请生成一个 Mermaid 格式的思维导图（mindmap）。严格遵循以下规则：\n\n");
        sb.append("1. 输出必须以 `mindmap` 关键字开头（不要包含 ```mermaid 等代码块标记）。\n");
        sb.append("2. 根节点为知识点名称。\n");
        sb.append("3. 使用缩进表示层级关系（每层缩进 2 个空格）。\n");
        sb.append("4. 节点文字简洁明了，每个节点控制在 10 个字以内。\n");
        sb.append("5. 层次控制在 3-4 层以内，不要太深。\n\n");
        sb.append("示例格式：\n");
        sb.append("mindmap\n");
        sb.append("  知识点名称\n");
        sb.append("    分支一\n");
        sb.append("      子要点 A\n");
        sb.append("      子要点 B\n");
        sb.append("    分支二\n");
        sb.append("      子要点 C\n\n");
        sb.append("请直接输出 Mermaid mindmap 代码，不要包含任何解释性文字或代码块标记。");

        return sb.toString();
    }

    private void appendBlueprintSections(StringBuilder sb,
                                          List<ResourceBlueprint.BlueprintSection> sections,
                                          int indent) {
        for (ResourceBlueprint.BlueprintSection section : sections) {
            sb.append("  ".repeat(indent));
            sb.append("- ").append(section.heading()).append("\n");
            if (section.children() != null && !section.children().isEmpty()) {
                appendBlueprintSections(sb, section.children(), indent + 1);
            }
        }
    }
}
