package org.example.educatorweb.resourcegen.agents.generators;

import org.example.educatorweb.common.model.ResourceType;
import org.example.educatorweb.knowledgegraph.model.KnowledgeContext;
import org.example.educatorweb.profile.model.StudentProfile;
import org.example.educatorweb.resourcegen.config.ModelRegistry;
import org.example.educatorweb.resourcegen.infrastructure.ModelProvider;
import org.example.educatorweb.resourcegen.model.GenerationState;
import org.example.educatorweb.resourcegen.model.ResourceBlueprint;
import org.springframework.stereotype.Component;

@Component
public class QuizGenerator extends AbstractGenerator {

    public QuizGenerator(ModelRegistry registry) {
        super(registry, ResourceType.QUIZ);
    }

    @Override
    protected String doGenerate(GenerationState state) {
        String prompt = buildPrompt(state);
        log.info("QuizGenerator: sending prompt to LLM for topic={} (prompt length={})",
            state.knowledgePoint(), prompt.length());

        ModelProvider provider = registry.resolve(supportedType());
        String response = provider.chat(prompt);
        log.info("QuizGenerator: received LLM response (length={})",
            response != null ? response.length() : 0);

        if (response == null || response.isBlank()) {
            log.warn("QuizGenerator: empty response from LLM, using fallback");
            return buildFallbackQuiz(state);
        }

        String cleaned = cleanResponse(response);
        if (!cleaned.trim().startsWith("{")) {
            log.warn("QuizGenerator: response is not JSON, using fallback. Raw start: {}",
                cleaned.substring(0, Math.min(80, cleaned.length())));
            return buildFallbackQuiz(state);
        }

        return cleaned;
    }

    @Override
    protected String getFormatHint() {
        return "JSON quiz";
    }

    private String buildPrompt(GenerationState state) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一位资深的教育测评专家，擅长设计各种类型的测验题目。\n\n");

        // --- Student profile (D1 used for difficulty personalization) ---
        sb.append("## 学生画像\n");
        StudentProfile profile = state.profile();
        String difficultyHint = "中等";
        if (profile != null) {
            sb.append("- 知识基础: ").append(profile.getKnowledgeBaseLevel() != null
                ? profile.getKnowledgeBaseLevel() : "未评估").append("\n");
            sb.append("- 认知风格: ").append(profile.getCognitiveStyleType() != null
                ? profile.getCognitiveStyleType() : "未评估").append("\n");
            if (profile.getErrorPatternTags() != null
                    && !profile.getErrorPatternTags().isEmpty()) {
                sb.append("- 常见错误模式: ").append(profile.getErrorPatternTags()).append("\n");
            }
            sb.append("- 学习节奏: ").append(profile.getLearningPaceType() != null
                ? profile.getLearningPaceType() : "未评估").append("\n");
            sb.append("- 内容偏好: ").append(profile.getContentPreferenceType() != null
                ? profile.getContentPreferenceType() : "未评估").append("\n");
            sb.append("- 学习目标: ").append(profile.getGoalOrientationType() != null
                ? profile.getGoalOrientationType() : "未评估").append("\n");

            // Personalize difficulty based on knowledgeBase level
            if (profile.getKnowledgeBaseLevel() != null) {
                String level = profile.getKnowledgeBaseLevel();
                difficultyHint = switch (level) {
                    case "优秀", "熟练" -> "较难";
                    case "一般" -> "中等";
                    case "薄弱", "入门" -> "较易";
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
            sb.append("请围绕以下大纲设计测验：\n\n");
            for (ResourceBlueprint.BlueprintSection section : blueprint.sections()) {
                sb.append("- ").append(section.heading()).append(": ").append(section.keyPoints()).append("\n");
            }
        } else {
            sb.append("请围绕知识点「").append(state.knowledgePoint()).append("」自行设计测验结构。\n");
        }

        // --- Knowledge point ---
        sb.append("\n## 目标知识点\n");
        sb.append(state.knowledgePoint()).append("\n");

        // --- Difficulty guidance ---
        sb.append("\n## 难度设定\n");
        sb.append("根据学生画像，整体难度应为：").append(difficultyHint).append("\n");

        // --- Output requirements ---
        sb.append("\n## 输出要求\n");
        sb.append("请生成一份包含 7 道题目的测验，严格输出 JSON 格式。题型分配如下：\n\n");
        sb.append("- **3 道** 选择题（Multiple Choice, MC）：每题 4 个选项，只有一个正确答案\n");
        sb.append("- **2 道** 判断题（True/False, T/F）\n");
        sb.append("- **1 道** 简答题（Short Answer）\n");
        sb.append("- **1 道** 填空题（Fill-in-the-blank）\n\n");

        sb.append("题目应覆盖不同的认知层次（记忆、理解、应用、分析），难度控制在「").append(difficultyHint).append("」水平。\n\n");

        sb.append("输出 JSON 格式（不要包含代码块标记）：\n\n");
        sb.append("{\n");
        sb.append("  \"title\": \"测验标题\",\n");
        sb.append("  \"questions\": [\n");
        sb.append("    {\n");
        sb.append("      \"type\": \"MC\" | \"TF\" | \"SHORT_ANSWER\" | \"FILL_BLANK\",\n");
        sb.append("      \"question\": \"题目内容\",\n");
        sb.append("      \"options\": [\"A选项\", \"B选项\", \"C选项\", \"D选项\"], // 仅 MC 需要\n");
        sb.append("      \"answer\": \"正确答案\",\n");
        sb.append("      \"explanation\": \"答案解析\",\n");
        sb.append("      \"difficulty\": \"easy\" | \"medium\" | \"hard\",\n");
        sb.append("      \"relatedConcept\": \"相关知识点名称\"\n");
        sb.append("    }\n");
        sb.append("  ]\n");
        sb.append("}\n\n");

        sb.append("请直接输出 JSON，不要包含任何解释性前言或后记，不要使用 ```json 代码块。");

        return sb.toString();
    }

    private String cleanResponse(String response) {
        String cleaned = response.trim();
        if (cleaned.startsWith("```json")) {
            cleaned = cleaned.substring(7);
        } else if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring(3);
        }
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - 3);
        }
        return cleaned.trim();
    }

    private String buildFallbackQuiz(GenerationState state) {
        String topic = state.knowledgePoint();
        // Build a deterministic fallback JSON with 7 questions
        return String.format("""
            {
              "title": "%1$s - 基础测验",
              "questions": [
                {
                  "type": "MC",
                  "question": "以下关于「%1$s」的描述，哪一项是正确的？",
                  "options": ["A. 是一项无关紧要的概念", "B. 是计算机科学的基础知识", "C. 仅适用于特定的编程语言", "D. 已被现代技术淘汰"],
                  "answer": "B",
                  "explanation": "该知识点是相关领域的基础概念，具有广泛的应用价值。",
                  "difficulty": "easy",
                  "relatedConcept": "%1$s"
                },
                {
                  "type": "MC",
                  "question": "在学习「%1$s」之前，最需要掌握的前置知识是什么？",
                  "options": ["A. 高等数学", "B. 基本概念和术语", "C. 量子力学", "D. 无需任何基础"],
                  "answer": "B",
                  "explanation": "理解基本概念和术语是深入学习的前提。",
                  "difficulty": "easy",
                  "relatedConcept": "前置知识"
                },
                {
                  "type": "MC",
                  "question": "下列哪个选项最能体现「%1$s」的核心价值？",
                  "options": ["A. 提高代码运行速度", "B. 建立系统化的知识框架", "C. 减少开发成本", "D. 替代传统方法"],
                  "answer": "B",
                  "explanation": "系统化的知识框架有助于深入理解和灵活应用。",
                  "difficulty": "medium",
                  "relatedConcept": "%1$s"
                },
                {
                  "type": "TF",
                  "question": "掌握「%1$s」需要大量的实践经验积累。",
                  "answer": "True",
                  "explanation": "理论与实践相结合是掌握该知识点的关键。",
                  "difficulty": "easy",
                  "relatedConcept": "学习策略"
                },
                {
                  "type": "TF",
                  "question": "「%1$s」只适用于特定场景，在其他领域完全无用。",
                  "answer": "False",
                  "explanation": "该知识点具有跨领域的应用价值。",
                  "difficulty": "easy",
                  "relatedConcept": "应用场景"
                },
                {
                  "type": "SHORT_ANSWER",
                  "question": "请简要说明「%1$s」的三个主要特点。",
                  "answer": "1. 具有清晰的定义和边界；2. 与其他知识点存在关联；3. 可在实践中验证和应用",
                  "explanation": "从定义、关联性和实践性三个维度理解该知识点。",
                  "difficulty": "medium",
                  "relatedConcept": "%1$s"
                },
                {
                  "type": "FILL_BLANK",
                  "question": "「%1$s」的核心在于建立____的知识体系，从而提升学习效率。",
                  "answer": "系统化",
                  "explanation": "系统化的知识体系是高效学习的基础。",
                  "difficulty": "medium",
                  "relatedConcept": "学习方法"
                }
              ]
            }""", topic);
    }
}
