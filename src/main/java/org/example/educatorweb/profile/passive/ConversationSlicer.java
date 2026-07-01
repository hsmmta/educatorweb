package org.example.educatorweb.profile.passive;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.educatorweb.aitutor.config.ChromaClient;
import org.example.educatorweb.resourcegen.infrastructure.DeepSeekProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Hybrid conversation slicer. Default unit is one conversation.
 * Long conversations ({@code longConversationThreshold} rounds) are
 * semantically sliced by detecting topic boundaries via LLM.
 */
@Component
public class ConversationSlicer {

    private static final Logger log = LoggerFactory.getLogger(ConversationSlicer.class);
    /** Each conversation round consists of one user message + one assistant reply. */
    private static final int MESSAGES_PER_ROUND = 2;

    private final ChromaClient chromaClient;
    private final DeepSeekProvider llmProvider;
    private final ObjectMapper objectMapper;
    private final int longConversationThreshold;

    public ConversationSlicer(ChromaClient chromaClient,
                               DeepSeekProvider llmProvider,
                               ObjectMapper objectMapper,
                               @Value("${profile.passive.long-conversation:20}") int longConversationThreshold) {
        this.chromaClient = chromaClient;
        this.llmProvider = llmProvider;
        this.objectMapper = objectMapper;
        this.longConversationThreshold = longConversationThreshold;
    }

    /**
     * Slice conversations into semantic segments.
     */
    public List<Slice> slice(String studentId, List<String> conversationIds) {
        List<Slice> slices = new ArrayList<>();
        for (String convId : conversationIds) {
            try {
                List<Map<String, Object>> messages = chromaClient.getConversationMessages(convId, studentId);
                if (messages.isEmpty()) continue;

                int rounds = countUserRounds(messages);
                String maxTimestamp = extractMaxTimestamp(messages);

                if (rounds <= longConversationThreshold) {
                    String text = buildConversationText(messages);
                    slices.add(new Slice(convId, text, null, maxTimestamp));
                } else {
                    List<TopicBoundary> boundaries = detectTopicBoundaries(messages);
                    for (TopicBoundary b : boundaries) {
                        int fromIndex = Math.max(0, (b.startRound - 1) * MESSAGES_PER_ROUND);
                        int toIndex = Math.min(b.endRound * MESSAGES_PER_ROUND, messages.size());
                        if (fromIndex >= toIndex) {
                            log.warn("ConversationSlicer: invalid boundary start={} end={} for conv={} ({} msgs, {} rounds)",
                                b.startRound, b.endRound, convId, messages.size(), rounds);
                            continue;
                        }
                        List<Map<String, Object>> segment = messages.subList(fromIndex, toIndex);
                        String text = buildConversationText(segment);
                        String segMaxTs = extractMaxTimestamp(segment);
                        slices.add(new Slice(convId, text, b.topic, segMaxTs));
                    }
                }
            } catch (Exception e) {
                log.warn("ConversationSlicer: failed to slice conv={}: {}", convId, e.getMessage());
            }
        }
        log.info("ConversationSlicer: {} conversations → {} slices", conversationIds.size(), slices.size());
        return slices;
    }

    // ---- private helpers ----

    private int countUserRounds(List<Map<String, Object>> messages) {
        int count = 0;
        for (Map<String, Object> msg : messages) {
            Object meta = msg.get("metadata");
            if (meta instanceof Map<?, ?> m && "user".equals(m.get("role"))) count++;
        }
        return count;
    }

    private String extractMaxTimestamp(List<Map<String, Object>> messages) {
        String max = null;
        for (Map<String, Object> msg : messages) {
            Object meta = msg.get("metadata");
            if (meta instanceof Map<?, ?> m) {
                Object ts = m.get("timestamp");
                if (ts != null) {
                    String tsStr = ts.toString();
                    if (max == null || tsStr.compareTo(max) > 0) max = tsStr;
                }
            }
        }
        return max;
    }

    private String buildConversationText(List<Map<String, Object>> messages) {
        StringBuilder sb = new StringBuilder(messages.size() * 200);
        int round = 1;
        for (Map<String, Object> msg : messages) {
            Object meta = msg.get("metadata");
            String document = (String) msg.getOrDefault("document", "");
            if (meta instanceof Map<?, ?> m && document != null && !document.isBlank()) {
                String role = (String) m.get("role");
                String prefix = "user".equals(role) ? "学生" : "助教";
                sb.append("[").append(round).append("] ").append(prefix).append(": ").append(document).append("\n\n");
            }
            // Advance round counter on each assistant message regardless of blank documents
            if (meta instanceof Map<?, ?> m && "assistant".equals(m.get("role"))) round++;
        }
        return sb.toString();
    }

    private List<TopicBoundary> detectTopicBoundaries(List<Map<String, Object>> messages) {
        int rounds = countUserRounds(messages);
        String convoText = buildConversationText(messages);

        String prompt = String.format("""
            以下是学生与AI助教的对话记录（共%d轮）。请分析对话中的话题切换点，
            将对话切分为语义连贯的片段。只返回JSON数组，不要其他内容。

            对话记录：
            %s

            输出格式：
            [{"startRound":1,"endRound":12,"topic":"话题简述"},
             {"startRound":13,"endRound":%d,"topic":"话题简述"}]

            规则：
            - 一个片段至少包含3轮对话
            - 话题切换后必须切分
            - 如果全篇话题一致，返回单个片段
            - topic用简短中文概括（不超过15字）
            """, rounds, convoText, rounds);

        log.debug("ConversationSlicer: detecting boundaries for {} rounds", rounds);
        try {
            String response = llmProvider.chat(prompt);
            if (response == null || response.isBlank()) return List.of(singleSlice(rounds));

            // Clean response
            String json = response.trim();
            if (json.startsWith("```")) {
                json = json.replaceFirst("```json\\s*", "").replaceFirst("```\\s*$", "").trim();
            }
            int bracketStart = json.indexOf('[');
            int bracketEnd = json.lastIndexOf(']');
            if (bracketStart >= 0 && bracketEnd > bracketStart) {
                json = json.substring(bracketStart, bracketEnd + 1);
            }

            List<TopicBoundary> boundaries = objectMapper.readValue(json,
                new TypeReference<List<TopicBoundary>>() {});
            if (boundaries == null || boundaries.isEmpty()) return List.of(singleSlice(rounds));
            return boundaries;
        } catch (Exception e) {
            log.warn("ConversationSlicer: boundary detection failed, fallback to single slice: {}", e.getMessage());
            return List.of(singleSlice(rounds));
        }
    }

    private TopicBoundary singleSlice(int rounds) {
        return new TopicBoundary(1, rounds, null);
    }

    // ---- inner types ----

    /** LLM-detected topic boundary. */
    public record TopicBoundary(int startRound, int endRound, String topic) {}

    /** A semantic conversation segment for profile extraction. */
    public record Slice(
        String conversationId,
        String text,
        String topic,
        String maxTimestamp
    ) {}
}
