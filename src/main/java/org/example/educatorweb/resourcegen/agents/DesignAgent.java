package org.example.educatorweb.resourcegen.agents;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.educatorweb.common.model.ResourceType;
import org.example.educatorweb.knowledgegraph.model.KnowledgeContext;
import org.example.educatorweb.profile.model.StudentProfile;
import org.example.educatorweb.rag.model.DocumentSnippet;
import org.example.educatorweb.resourcegen.config.ModelRegistry;
import org.example.educatorweb.resourcegen.infrastructure.ModelProvider;
import org.example.educatorweb.resourcegen.model.GenerationState;
import org.example.educatorweb.resourcegen.model.ProgressStage;
import org.example.educatorweb.resourcegen.model.ResourceBlueprint;
import org.example.educatorweb.resourcegen.orchestration.AgentNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class DesignAgent implements AgentNode {
    private static final Logger log = LoggerFactory.getLogger(DesignAgent.class);

    private final ModelRegistry registry;
    private final ObjectMapper objectMapper;

    public DesignAgent(ModelRegistry registry, ObjectMapper objectMapper) {
        this.registry = registry;
        this.objectMapper = objectMapper;
    }

    @Override
    public GenerationState execute(GenerationState state) {
        String prompt = buildPrompt(state);
        log.info("DesignAgent: sending prompt to LLM for topic={} (prompt length={})",
            state.knowledgePoint(), prompt.length());

        try {
            ModelProvider provider = registry.resolve(ResourceType.DOC); // use text model for blueprint design
            String response = provider.chat(prompt);
            log.info("DesignAgent: received LLM response (length={})", response != null ? response.length() : 0);

            ResourceBlueprint blueprint = parseBlueprint(response);
            return state.withBlueprint(blueprint).withStage(ProgressStage.GENERATING);
        } catch (Exception e) {
            log.error("DesignAgent: LLM call or parsing failed: {}", e.getMessage(), e);
            ResourceBlueprint fallback = createFallbackBlueprint(state);
            return state.withBlueprint(fallback).withStage(ProgressStage.GENERATING);
        }
    }

    private ResourceBlueprint parseBlueprint(String response) {
        if (response == null || response.isBlank()) {
            throw new IllegalStateException("LLM returned empty response");
        }
        // Strip markdown code fences if present
        String json = response.trim();
        if (json.startsWith("```json")) {
            json = json.substring(7);
        } else if (json.startsWith("```")) {
            json = json.substring(3);
        }
        if (json.endsWith("```")) {
            json = json.substring(0, json.length() - 3);
        }
        json = json.trim();

        try {
            return objectMapper.readValue(json, ResourceBlueprint.class);
        } catch (JsonProcessingException e) {
            log.warn("DesignAgent: failed to parse JSON, raw response snippet: {}",
                response.substring(0, Math.min(200, response.length())));
            throw new RuntimeException("Failed to parse ResourceBlueprint JSON: " + e.getMessage(), e);
        }
    }

    private ResourceBlueprint createFallbackBlueprint(GenerationState state) {
        return new ResourceBlueprint(
            state.knowledgePoint() + " 学习资料",
            "自动生成的教学资源大纲",
            List.of(new ResourceBlueprint.BlueprintSection(
                "核心概念", 1, state.knowledgePoint(), List.of())),
            new HashMap<>(),
            List.of(),
            Instant.now()
        );
    }

    private String buildPrompt(GenerationState state) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一位资深的机器学习教育专家，负责设计教学资源大纲。\n\n");

        // --- Student profile ---
        sb.append("## 学生画像\n");
        StudentProfile profile = state.profile();
        if (profile != null) {
            sb.append("- 知识基础: ").append(profile.knowledgeBase()).append("\n");
            sb.append("- 认知风格: ").append(profile.cognitiveStyle()).append("\n");
            sb.append("- 常见错误模式: ").append(profile.errorPattern()).append("\n");
            sb.append("- 学习节奏: ").append(profile.learningPace()).append("\n");
            sb.append("- 内容偏好: ").append(profile.contentPreference()).append("\n");
            sb.append("- 学习目标: ").append(profile.goalOrientation()).append("\n");
        } else {
            sb.append("（无可用画像数据，请使用默认难度和通用风格）\n");
        }

        // --- Knowledge graph context ---
        sb.append("\n## 知识点图谱\n");
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
            for (int i = 0; i < ragContexts.size(); i++) {
                DocumentSnippet snippet = ragContexts.get(i);
                sb.append(i + 1).append(". [").append(snippet.source()).append("] ")
                  .append(snippet.content()).append("\n");
            }
        } else {
            sb.append("（无可用参考资料）\n");
        }

        // --- Knowledge point ---
        sb.append("\n## 当前知识点\n");
        sb.append(state.knowledgePoint()).append("\n");

        // --- Requested resource types ---
        sb.append("\n## 请求生成的资源类型\n");
        for (ResourceType type : state.types()) {
            sb.append("- ").append(type.name()).append("\n");
        }

        // --- Output format instructions ---
        sb.append("\n## 任务要求\n");
        sb.append("请根据以上信息，设计一个教学资源大纲（ResourceBlueprint），");
        sb.append("以JSON格式输出。**只输出JSON，不要包含任何其他文字、解释或markdown代码块标记。**\n\n");
        sb.append("JSON必须符合以下结构（使用Jackson record反序列化）：\n\n");

        sb.append("""
{
  "title": "教学资源标题（字符串）",
  "summary": "资源摘要说明（字符串）",
  "sections": [
    {
      "heading": "小节标题",
      "depth": 1,
      "keyPoints": "本节核心要点概述",
      "children": [
        {
          "heading": "子标题",
          "depth": 2,
          "keyPoints": "子节要点",
          "children": []
        }
      ]
    }
  ],
  "resourcePlans": {
    "DOC": {
      "promptFocus": "文档应重点涵盖的内容方向",
      "keyPoints": ["要点1", "要点2"],
      "formatHint": "格式建议，如'Markdown'、'LaTeX公式'"
    },
    "QUIZ": {
      "promptFocus": "测验应侧重的考察方向",
      "keyPoints": ["考察点1", "考察点2"],
      "formatHint": "格式建议，如'选择题+简答题'"
    }
  },
  "adjustments": [
    {
      "dimension": "难度",
      "description": "根据学生基础适当降低理论推导难度",
      "effect": "减少数学证明，增加直观解释"
    }
  ],
  "createdAt": "2025-01-01T00:00:00Z"
}
""");

        // --- Few-shot example ---
        sb.append("\n## 示例（以便你理解输出格式）\n");
        sb.append("输入：知识点=支持向量机(SVM)，学生=初学者，资源类型=[DOC, QUIZ]\n\n");
        sb.append("""
{
  "title": "支持向量机(SVM)从入门到实践",
  "summary": "系统讲解SVM的核心原理、数学推导和实际应用，配套测验巩固关键概念",
  "sections": [
    {
      "heading": "SVM核心思想",
      "depth": 1,
      "keyPoints": "介绍分类问题、超平面和最大间隔的思想",
      "children": [
        {
          "heading": "线性可分SVM",
          "depth": 2,
          "keyPoints": "硬间隔最大化，支持向量的定义",
          "children": []
        },
        {
          "heading": "线性不可分SVM",
          "depth": 2,
          "keyPoints": "软间隔、松弛变量、惩罚参数C",
          "children": []
        }
      ]
    },
    {
      "heading": "数学基础",
      "depth": 1,
      "keyPoints": "拉格朗日对偶性、KKT条件、凸优化基础",
      "children": []
    },
    {
      "heading": "核方法与非线性SVM",
      "depth": 1,
      "keyPoints": "核技巧原理、常见核函数选择",
      "children": []
    },
    {
      "heading": "SVM实践",
      "depth": 1,
      "keyPoints": "sklearn调用、参数调优、多分类扩展",
      "children": []
    }
  ],
  "resourcePlans": {
    "DOC": {
      "promptFocus": "从直觉到数学再到代码的三层递进讲解",
      "keyPoints": ["最大间隔原理", "对偶问题推导", "核函数选择指南"],
      "formatHint": "Markdown文档，包含数学公式(LaTeX)和Python代码片段"
    },
    "QUIZ": {
      "promptFocus": "检验SVM核心概念的理解程度",
      "keyPoints": ["支持向量的识别", "核函数选择依据", "C参数影响"],
      "formatHint": "包含5道选择题和2道简答题"
    }
  },
  "adjustments": [
    {
      "dimension": "难度",
      "description": "学生基础一般，简化数学推导",
      "effect": "侧重几何直觉，减少严格证明步骤"
    },
    {
      "dimension": "节奏",
      "description": "学生偏好稳扎稳打型学习",
      "effect": "每个概念提供充分示例，循序渐进"
    }
  ],
  "createdAt": "2025-06-04T00:00:00Z"
}
""");

        sb.append("\n现在，请为目标知识点「").append(state.knowledgePoint()).append("」生成相应的JSON。");

        return sb.toString();
    }
}
