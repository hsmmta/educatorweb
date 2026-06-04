package org.example.educatorweb.resourcegen.agents.generators;

import org.example.educatorweb.common.model.ResourceType;
import org.example.educatorweb.knowledgegraph.model.KnowledgeContext;
import org.example.educatorweb.profile.model.StudentProfile;
import org.example.educatorweb.resourcegen.infrastructure.CodeSandboxService;
import org.example.educatorweb.resourcegen.infrastructure.CodeSandboxService.ExecutionResult;
import org.example.educatorweb.resourcegen.infrastructure.SandboxTemplate;
import org.example.educatorweb.resourcegen.model.GenerationState;
import org.example.educatorweb.resourcegen.model.ResourceBlueprint;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

/**
 * Generator for Python code resources.
 * Generates code via LLM, executes it in the sandbox, and embeds the execution
 * output as comments in the final result.
 */
@Component
public class CodeGenerator extends AbstractGenerator {

    private final CodeSandboxService sandbox;

    public CodeGenerator(ChatClient chatClient, CodeSandboxService sandbox) {
        super(chatClient, ResourceType.CODE);
        this.sandbox = sandbox;
    }

    @Override
    protected String doGenerate(GenerationState state) {
        String prompt = buildPrompt(state);
        log.info("CodeGenerator: sending prompt to LLM for topic={} (prompt length={})",
            state.knowledgePoint(), prompt.length());

        String response = chatClient.prompt().user(prompt).call().content();
        log.info("CodeGenerator: received LLM response (length={})",
            response != null ? response.length() : 0);

        if (response == null || response.isBlank()) {
            log.warn("CodeGenerator: empty response from LLM, using fallback");
            return buildFallbackCode(state);
        }

        String cleaned = stripCodeFences(response);

        // Execute in sandbox
        ExecutionResult execResult = sandbox.execute(cleaned);
        log.info("CodeGenerator: sandbox execution result: exitCode={} timedOut={}",
            execResult.exitCode(), execResult.timedOut());

        // Embed execution result as metadata comments
        return embedExecutionResult(cleaned, execResult);
    }

    @Override
    protected String getFormatHint() {
        return "Python code";
    }

    private String buildPrompt(GenerationState state) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一位资深的 Python 编程教育专家，擅长编写简洁、可读、教学性强的代码示例。\n\n");

        // --- Student profile ---
        sb.append("## 学生画像\n");
        StudentProfile profile = state.profile();
        String levelHint = "中等";
        if (profile != null) {
            sb.append("- 知识基础: ").append(profile.knowledgeBase() != null
                ? profile.knowledgeBase().level() : "未评估").append("\n");
            sb.append("- 认知风格: ").append(profile.cognitiveStyle() != null
                ? profile.cognitiveStyle().type() : "未评估").append("\n");
            if (profile.errorPattern() != null && profile.errorPattern().tags() != null
                    && !profile.errorPattern().tags().isEmpty()) {
                sb.append("- 常见错误模式: ").append(profile.errorPattern().tags()).append("\n");
            }
            sb.append("- 学习节奏: ").append(profile.learningPace() != null
                ? profile.learningPace().type() : "未评估").append("\n");
            sb.append("- 学习目标: ").append(profile.goalOrientation() != null
                ? profile.goalOrientation().type() : "未评估").append("\n");

            if (profile.knowledgeBase() != null) {
                levelHint = switch (profile.knowledgeBase().level()) {
                    case "优秀", "熟练" -> "进阶（可涉及高级算法和优化）";
                    case "一般" -> "中等（适当的算法实现和解释）";
                    case "薄弱", "入门" -> "基础（简单清晰的示例，注重可理解性）";
                    default -> "中等";
                };
            }
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

        // --- Blueprint sections ---
        sb.append("\n## 内容大纲\n");
        ResourceBlueprint blueprint = state.blueprint();
        if (blueprint != null && blueprint.sections() != null && !blueprint.sections().isEmpty()) {
            sb.append("请围绕以下大纲编写代码：\n\n");
            for (ResourceBlueprint.BlueprintSection section : blueprint.sections()) {
                sb.append("- ").append(section.heading()).append(": ").append(section.keyPoints()).append("\n");
            }
        } else {
            sb.append("请围绕知识点「").append(state.knowledgePoint()).append("」自行组织代码结构。\n");
        }

        // --- Knowledge point ---
        sb.append("\n## 目标知识点\n");
        sb.append(state.knowledgePoint()).append("\n");

        // --- Difficulty ---
        sb.append("\n## 难度设定\n");
        sb.append("根据学生画像，代码难度应为：").append(levelHint).append("\n");

        // --- Sandbox constraints ---
        sb.append("\n");
        sb.append(SandboxTemplate.availableFeatures());
        sb.append("\n");

        // --- Output requirements ---
        sb.append("## 输出要求\n\n");
        sb.append("请生成一份教学用的 Python 代码，要求如下：\n\n");
        sb.append("1. **注释丰富**: 每段核心逻辑都要有中文注释说明\n");
        sb.append("2. **docstring**: 每个函数/类都要有 docstring\n");
        sb.append("3. **完整可运行**: 代码必须是完整的、可以直接运行的\n");
        sb.append("4. **包含示例**: 在代码底部用 `if __name__ == '__main__':` 块展示用法\n");
        sb.append("5. **打印结果**: 使用 print() 输出关键结果\n");
        sb.append("6. **仅使用标准库**: 不使用任何第三方包\n");
        sb.append("7. **不使用 input()**: 不要使用交互式输入\n\n");

        sb.append("请直接输出 Python 代码，不要包含 ```python 代码块标记，不要包含解释性前言或后记。");

        return sb.toString();
    }

    private String stripCodeFences(String content) {
        String cleaned = content.trim();
        if (cleaned.startsWith("```python")) {
            cleaned = cleaned.substring(9);
        } else if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring(3);
        }
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - 3);
        }
        return cleaned.trim();
    }

    private String embedExecutionResult(String code, ExecutionResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ============================================================\n");
        sb.append("# Sandbox Execution Result\n");
        sb.append("# Exit Code: ").append(result.exitCode()).append("\n");
        sb.append("# Execution Time: ").append(result.executionTimeMs()).append(" ms\n");
        sb.append("# Timed Out: ").append(result.timedOut()).append("\n");
        if (result.stdout() != null && !result.stdout().isEmpty()) {
            sb.append("# \n");
            sb.append("# --- stdout ---\n");
            for (String line : result.stdout().split("\n")) {
                sb.append("# ").append(line).append("\n");
            }
        }
        if (result.stderr() != null && !result.stderr().isEmpty()) {
            sb.append("# \n");
            sb.append("# --- stderr ---\n");
            for (String line : result.stderr().split("\n")) {
                sb.append("# ").append(line).append("\n");
            }
        }
        sb.append("# ============================================================\n\n");
        sb.append(code);
        return sb.toString();
    }

    private String buildFallbackCode(GenerationState state) {
        String topic = state.knowledgePoint();
        return String.format("""
            # ============================================================
            # Sandbox Execution Result
            # Exit Code: 0
            # Execution Time: 0 ms
            # Timed Out: false
            #
            # --- stdout ---
            # 这是关于「%1$s」的生成代码（LLM 未返回有效响应）
            # ============================================================

            def hello_%1$s():
                \"\"\"关于「%1$s」的基础示例。\"\"\"
                print("欢迎学习 %1$s！")
                print("LLM 生成失败，这是备用代码。")
                return 42

            if __name__ == '__main__':
                result = hello_%1$s()
                print(f"返回值: {result}")
            """, topic.replace("-", "_").replace(" ", "_"));
    }
}
