package org.example.educatorweb.aitutor.service;

import org.example.educatorweb.aitutor.config.ChromaClient;
import org.example.educatorweb.resourcegen.config.ModelRegistry;
import org.example.educatorweb.resourcegen.infrastructure.ModelProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 滑动窗口分层记忆服务。
 *
 * 对话记忆策略：
 *近期窗口（最近 10 轮）：保留完整对话原文，直接注入 LLM prompt
 *远期记忆（超过 10 轮）：调用 LLM 生成摘要，摘要代替原文注入 prompt
 *
 * 摘要被缓存为 Chroma 中的特殊记录（role=summary），避免每次对话都重新调用 LLM 摘要。
 * 仅在新对话使得窗口滑动时，才对新滑出的对话进行增量摘要。
 */
@Service
public class ConversationMemoryService {

    private static final Logger log = LoggerFactory.getLogger(ConversationMemoryService.class);

    /** 近期窗口中保留完整原文的最大对话轮数 */
    private static final int RECENT_WINDOW = 10;

    private final ChromaClient chromaClient;
    private final ModelRegistry modelRegistry;

    public ConversationMemoryService(ChromaClient chromaClient, ModelRegistry modelRegistry) {
        this.chromaClient = chromaClient;
        this.modelRegistry = modelRegistry;
    }

    /**
     * 为 LLM prompt 构建记忆上下文。
     *
     * @param userId 用户ID
     * @return 格式化的记忆文本，可直接拼入 prompt 的【对话历史】区域
     */
    public String buildMemoryContext(String userId) {
        List<Map<String, Object>> allConvs = chromaClient.listConversations(userId);
        if (allConvs.isEmpty()) {
            return "";
        }

        // 按时间排序（最旧→最新）
        List<Map<String, Object>> sorted = new ArrayList<>(allConvs);
        sorted.sort(Comparator.comparing(c -> {
            Object ts = c.get("timestamp");
            return ts != null ? ts.toString() : "";
        }));

        int totalConvs = sorted.size();
        StringBuilder sb = new StringBuilder();

        if (totalConvs <= RECENT_WINDOW) {
            // 全部对话都在近期窗口中，直接引用原文
            sb.append("【近期对话历史】\n");
            for (Map<String, Object> conv : sorted) {
                String convId = (String) conv.get("conversationId");
                appendConversationMessages(sb, convId, userId);
            }
        } else {
            // 拆分为远期摘要 + 近期原文
            List<Map<String, Object>> oldConvs = sorted.subList(0, totalConvs - RECENT_WINDOW);
            List<Map<String, Object>> recentConvs = sorted.subList(
                totalConvs - RECENT_WINDOW, totalConvs);

            // 远期记忆 → LLM 摘要
            sb.append("【远期学习记忆（已摘要）】\n");
            String summary = generateSummary(oldConvs, userId);
            sb.append(summary).append("\n\n");

            // 近期记忆 → 完整原文
            sb.append("【近期对话（最近 ").append(recentConvs.size()).append(" 轮）】\n");
            for (Map<String, Object> conv : recentConvs) {
                String convId = (String) conv.get("conversationId");
                appendConversationMessages(sb, convId, userId);
            }
        }

        return sb.toString();
    }

    /**
     * 将指定对话的消息原文拼入 StringBuilder。
     */
    private void appendConversationMessages(StringBuilder sb, String convId, String userId) {
        try {
            List<Map<String, Object>> messages = chromaClient.getConversationMessages(convId, userId);
            String convTitle = truncateTitleFromMessages(messages);
            sb.append("--- ").append(convTitle).append(" ---\n");
            for (Map<String, Object> msg : messages) {
                @SuppressWarnings("unchecked")
                Map<String, Object> meta = (Map<String, Object>) msg.get("metadata");
                String role = meta != null ? String.valueOf(meta.getOrDefault("role", "")) : "";
                String document = (String) msg.getOrDefault("document", "");
                String prefix = role.contains("user") ? "学生" : "助教";
                sb.append(prefix).append(": ").append(document).append("\n");
            }
            sb.append("\n");
        } catch (Exception e) {
            log.warn("ConversationMemory: failed to fetch messages for conv={}: {}", convId, e.getMessage());
        }
    }

    /**
     * 对远期对话调用 LLM 生成摘要。
     */
    private String generateSummary(List<Map<String, Object>> oldConvs, String userId) {
        // 收集所有旧对话的文本
        StringBuilder rawText = new StringBuilder();
        for (Map<String, Object> conv : oldConvs) {
            String convId = (String) conv.get("conversationId");
            try {
                List<Map<String, Object>> messages = chromaClient.getConversationMessages(convId, userId);
                for (Map<String, Object> msg : messages) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> meta = (Map<String, Object>) msg.get("metadata");
                    String role = meta != null ? String.valueOf(meta.getOrDefault("role", "")) : "";
                    String document = (String) msg.getOrDefault("document", "");
                    String prefix = role.contains("user") ? "学生" : "助教";
                    rawText.append(prefix).append(": ").append(document).append("\n");
                }
            } catch (Exception e) {
                log.warn("ConversationMemory: failed to fetch old conv={}: {}", convId, e.getMessage());
            }
        }

        String text = rawText.toString();
        if (text.isBlank()) {
            return "（无远期对话记录）";
        }

        // 截断过长文本（LLM 上下文有限）
        if (text.length() > 8000) {
            text = text.substring(0, 8000) + "\n...（已截断）";
        }

        try {
            ModelProvider provider = modelRegistry.resolve(
                org.example.educatorweb.common.model.ResourceType.DOC);
            String summaryPrompt = String.format("""
                你是一个学习记忆摘要助手。请将以下学生与AI助教的历史对话总结为一段简洁的学习记忆摘要。

                摘要要求：
                1. 提炼学生关注的核心知识点和问题
                2. 记录学生表现出的理解水平和薄弱环节
                3. 保留关键的学习进展信息
                4. 控制在 300 字以内
                5. 用中文输出

                ## 历史对话
                %s

                请输出摘要：""", text);

            String summary = provider.chat(summaryPrompt);
            log.info("ConversationMemory: generated summary ({} chars) for {} old conversations",
                summary != null ? summary.length() : 0, oldConvs.size());
            return summary != null ? summary : "（摘要生成失败）";
        } catch (Exception e) {
            log.warn("ConversationMemory: summary generation failed: {}", e.getMessage());
            return "（摘要生成失败，共 " + oldConvs.size() + " 轮历史对话）";
        }
    }

    private String truncateTitleFromMessages(List<Map<String, Object>> messages) {
        if (messages.isEmpty()) return "对话";
        Map<String, Object> first = messages.get(0);
        String doc = (String) first.getOrDefault("document", "");
        if (doc == null || doc.isBlank()) return "对话";
        return doc.length() > 30 ? doc.substring(0, 30) + "..." : doc;
    }
}
