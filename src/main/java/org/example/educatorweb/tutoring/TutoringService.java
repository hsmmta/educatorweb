package org.example.educatorweb.tutoring;

import org.example.educatorweb.knowledgegraph.KnowledgeGraphService;
import org.example.educatorweb.knowledgegraph.model.KnowledgeContext;
import org.example.educatorweb.rag.RagService;
import org.example.educatorweb.rag.model.DocumentSnippet;
import org.example.educatorweb.resourcegen.infrastructure.DeepSeekProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 智能辅导服务。
 * 实现三级检索链：私有知识库(RAG) → 知识图谱(KG) → LLM直接回答。
 */
@Service
public class TutoringService {

    private static final Logger log = LoggerFactory.getLogger(TutoringService.class);

    private final RagService ragService;
    private final KnowledgeGraphService kgService;
    private final DeepSeekProvider llmProvider;

    public TutoringService(RagService ragService,
                           KnowledgeGraphService kgService,
                           DeepSeekProvider llmProvider) {
        this.ragService = ragService;
        this.kgService = kgService;
        this.llmProvider = llmProvider;
    }

    /**
     * 三级检索链 + LLM 生成答案。
     */
    public TutoringResponse answer(String studentId, String question) {
        List<RetrievalStep> retrievalLog = new ArrayList<>();
        StringBuilder context = new StringBuilder();
        String answerSource = "LLM知识库";

        // L1: 私有知识库 (RAG)
        retrievalLog.add(new RetrievalStep("L1_PRIVATE_KB", "私有智库检索", "loading"));
        try {
            List<DocumentSnippet> ragResults = ragService.retrieve(studentId, question, 5);
            if (!ragResults.isEmpty()) {
                // 过滤低相关性结果
                List<DocumentSnippet> relevant = ragResults.stream()
                    .filter(r -> r.score() >= 0.6)
                    .toList();

                if (!relevant.isEmpty()) {
                    context.append("## 来自你的私人智库的资料：\n");
                    for (DocumentSnippet snippet : relevant) {
                        context.append("- ").append(snippet.content()).append("\n");
                        context.append("  (来源: ").append(snippet.source())
                            .append(", 相关性: ").append(String.format("%.0f%%", snippet.score() * 100)).append(")\n");
                    }
                    retrievalLog.set(0, new RetrievalStep("L1_PRIVATE_KB",
                        "私有智库检索完成",
                        "done",
                        "找到 " + relevant.size() + " 份相关资料"));
                    answerSource = "私有智库";

                    // 计算最高相关性
                    double maxScore = relevant.stream().mapToDouble(DocumentSnippet::score).max().orElse(0);
                    if (maxScore < 0.7) {
                        // 相关性不够高，继续 L2
                        retrievalLog.add(new RetrievalStep("L2_KG", "知识图谱检索", "loading"));
                    }
                } else {
                    retrievalLog.set(0, new RetrievalStep("L1_PRIVATE_KB",
                        "私有智库无高相关结果",
                        "done",
                        "降级到知识图谱"));
                    retrievalLog.add(new RetrievalStep("L2_KG", "知识图谱检索", "loading"));
                }
            } else {
                retrievalLog.set(0, new RetrievalStep("L1_PRIVATE_KB",
                    "私有智库无相关资料", "done", "降级到知识图谱"));
                retrievalLog.add(new RetrievalStep("L2_KG", "知识图谱检索", "loading"));
            }
        } catch (Exception e) {
            log.warn("RAG retrieval failed: {}", e.getMessage());
            retrievalLog.set(0, new RetrievalStep("L1_PRIVATE_KB",
                "私有智库检索失败", "done", "降级到知识图谱"));
            retrievalLog.add(new RetrievalStep("L2_KG", "知识图谱检索", "loading"));
        }

        // L2: 知识图谱 (KG)
        try {
            KnowledgeContext kgContext = kgService.queryContext(question);
            if (kgContext != null) {
                StringBuilder kgInfo = new StringBuilder();
                kgInfo.append("## 知识图谱中的结构化信息：\n");
                if (kgContext.prerequisites() != null && !kgContext.prerequisites().isEmpty()) {
                    kgInfo.append("- 前置知识: ").append(String.join(" → ", kgContext.prerequisites())).append("\n");
                }
                if (kgContext.successors() != null && !kgContext.successors().isEmpty()) {
                    kgInfo.append("- 后继知识: ").append(String.join(" → ", kgContext.successors())).append("\n");
                }
                if (kgContext.relatedConcepts() != null && !kgContext.relatedConcepts().isEmpty()) {
                    kgInfo.append("- 相关概念: ").append(String.join(", ", kgContext.relatedConcepts())).append("\n");
                }
                kgInfo.append("- 难度等级: ").append("⭐".repeat(Math.max(1, kgContext.difficultyLevel()))).append("\n");

                context.append(kgInfo);

                // 更新 L2 状态
                if (retrievalLog.size() > 1 && "L2_KG".equals(retrievalLog.get(retrievalLog.size() - 1).id())) {
                    retrievalLog.set(retrievalLog.size() - 1, new RetrievalStep("L2_KG",
                        "知识图谱检索完成",
                        "done",
                        "找到相关知识节点"));
                }
                if (!"私有智库".equals(answerSource)) {
                    answerSource = "知识图谱";
                }
            } else {
                if (retrievalLog.size() > 1 && "L2_KG".equals(retrievalLog.get(retrievalLog.size() - 1).id())) {
                    retrievalLog.set(retrievalLog.size() - 1, new RetrievalStep("L2_KG",
                        "知识图谱中未找到", "done", "使用LLM通用知识"));
                }
            }
        } catch (Exception e) {
            log.warn("KG query failed: {}", e.getMessage());
            if (retrievalLog.size() > 1 && "L2_KG".equals(retrievalLog.get(retrievalLog.size() - 1).id())) {
                retrievalLog.set(retrievalLog.size() - 1, new RetrievalStep("L2_KG",
                    "知识图谱检索失败", "done", "使用LLM通用知识"));
            }
        }

        // L3: LLM 直接回答（兜底）
        retrievalLog.add(new RetrievalStep("L3_LLM", "生成AI回答", "loading"));

        String answer;
        try {
            String prompt = buildAnswerPrompt(question, context.toString());
            answer = llmProvider.chat(prompt);

            retrievalLog.set(retrievalLog.size() - 1, new RetrievalStep("L3_LLM",
                "回答生成完成", "done", "基于" + answerSource));
        } catch (Exception e) {
            log.error("LLM answer generation failed: {}", e.getMessage());
            answer = "抱歉，生成回答时遇到了问题，请稍后重试。";
            retrievalLog.set(retrievalLog.size() - 1, new RetrievalStep("L3_LLM",
                "回答生成失败", "error", e.getMessage()));
        }

        return new TutoringResponse(answer, answerSource, retrievalLog);
    }

    private String buildAnswerPrompt(String question, String context) {
        return String.format("""
            你是一个专业的AI学习辅导助手。请根据提供的参考资料回答学生的问题。

            ## 参考资料
            %s

            ## 学生问题
            %s

            ## 回答要求
            1. 优先使用参考资料中的信息，如果参考资料充分，要明确标注信息来源
            2. 如果参考资料不足以回答问题，可以结合你的知识进行补充，但要说明哪些是你额外补充的
            3. 使用 Markdown 格式组织回答，包含适当的标题、列表和强调
            4. 对于复杂概念，使用举例说明或类比的方式帮助理解
            5. 如果涉及公式，使用 LaTeX 格式 ($...$ 或 $$...$$)
            6. 回答末尾提供 2-3 个相关推荐问题

            请开始回答：""",
            context.isEmpty() ? "（无参考资料，请使用你的知识回答）" : context,
            question);
    }

    /**
     * 辅导回答结果。
     */
    public record TutoringResponse(String answer, String source, List<RetrievalStep> retrievalSteps) {
        public Map<String, Object> toMap() {
            return Map.of(
                "answer", answer,
                "source", source,
                "sourceType", source.contains("私有") ? "success" : "info",
                "retrievalSteps", retrievalSteps.stream()
                    .map(RetrievalStep::toMap)
                    .collect(Collectors.toList())
            );
        }
    }

    /**
     * 检索步骤记录。
     */
    public record RetrievalStep(String id, String text, String status, String source) {
        public RetrievalStep(String id, String text, String status) {
            this(id, text, status, "");
        }

        public Map<String, Object> toMap() {
            return Map.of(
                "id", id,
                "text", text,
                "status", status,
                "source", source != null ? source : ""
            );
        }
    }
}