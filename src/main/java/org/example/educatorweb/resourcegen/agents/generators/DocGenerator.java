package org.example.educatorweb.resourcegen.agents.generators;

import org.example.educatorweb.common.model.ResourceType;
import org.example.educatorweb.knowledgegraph.model.KnowledgeContext;
import org.example.educatorweb.profile.model.StudentProfile;
import org.example.educatorweb.rag.model.DocumentSnippet;
import org.example.educatorweb.resourcegen.config.ModelRegistry;
import org.example.educatorweb.resourcegen.infrastructure.ModelProvider;
import org.example.educatorweb.resourcegen.model.GenerationState;
import org.example.educatorweb.resourcegen.model.ResourceBlueprint;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class DocGenerator extends AbstractGenerator {

    public DocGenerator(ModelRegistry registry) {
        super(registry, ResourceType.DOC);
    }

    @Override
    protected String doGenerate(GenerationState state) {
        String prompt = buildPrompt(state);
        log.info("DocGenerator: sending prompt to LLM for topic={} (prompt length={})",
            state.knowledgePoint(), prompt.length());

        ModelProvider provider = registry.resolve(supportedType());
        String response = provider.chat(prompt);
        log.info("DocGenerator: received LLM response (length={})",
            response != null ? response.length() : 0);

        return response != null ? response : "";
    }

    @Override
    protected String getFormatHint() {
        return "Markdown";
    }

    private String buildPrompt(GenerationState state) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一位资深的教育内容创作者，擅长编写结构清晰、内容丰富的教学文档。\n\n");

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
                sb.append("- 前置知识（内容中可利用这些基础）: ").append(String.join(", ", kg.prerequisites())).append("\n");
            }
            if (!kg.successors().isEmpty()) {
                sb.append("- 后续知识（可为深入学习做铺垫）: ").append(String.join(", ", kg.successors())).append("\n");
            }
            if (!kg.relatedConcepts().isEmpty()) {
                sb.append("- 相关概念（可做对比或关联讲解）: ").append(String.join(", ", kg.relatedConcepts())).append("\n");
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
            sb.append("请按照以下大纲结构编写文档：\n\n");
            appendSections(sb, blueprint.sections(), 0);
        } else {
            sb.append("请围绕知识点「").append(state.knowledgePoint()).append("」自行组织内容结构。\n");
        }

        // --- Knowledge point ---
        sb.append("\n## 目标知识点\n");
        sb.append(state.knowledgePoint()).append("\n");

        // --- Output requirements ---
        sb.append("\n## 输出要求\n");
        sb.append("请生成一份结构完整、内容充实的教学文档。必须使用 Markdown 格式，并遵循以下规范：\n\n");
        sb.append("1. **标题层级**: 使用 `#`、`##`、`###` 等标题标记，层次清晰。\n");
        sb.append("2. **数学公式**: 使用 LaTeX 语法，行内公式用 `$...$`，独立公式用 `$$...$$`。\n");
        sb.append("3. **代码示例**: 使用 Markdown 代码块（如 ```python ... ```），展示关键算法的 Python 实现。\n");
        sb.append("4. **表格**: 使用 Markdown 表格语法（`| 列1 | 列2 |`），用于对比、总结。\n");
        sb.append("5. **列表**: 使用有序/无序列表组织条目。\n");
        sb.append("6. **重点强调**: 使用 **粗体** 和 `行内代码` 突出关键概念。\n");
        sb.append("7. **小结**: 每节末尾可加入简短的「关键要点」总结。\n");
        sb.append("8. **练习题**: 文档末尾可附带 2-3 道思考题或练习题。\n\n");
        sb.append("请直接输出 Markdown 内容，不要包含解释性前言或后记。");

        return sb.toString();
    }

    private void appendSections(StringBuilder sb, List<ResourceBlueprint.BlueprintSection> sections, int indent) {
        for (ResourceBlueprint.BlueprintSection section : sections) {
            sb.append("  ".repeat(indent));
            sb.append("- **").append(section.heading()).append("**: ").append(section.keyPoints()).append("\n");
            if (section.children() != null && !section.children().isEmpty()) {
                appendSections(sb, section.children(), indent + 1);
            }
        }
    }
}
