package org.example.educatorweb.topicpush.service;

import org.example.educatorweb.rag.service.EmbeddingService;
import org.example.educatorweb.resourcegen.infrastructure.ModelProvider;
import org.example.educatorweb.topicpush.model.TopicCache;
import org.example.educatorweb.topicpush.repository.TopicCacheRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects topic shifts within a conversation by comparing the cosine similarity
 * of consecutive question embeddings. When a shift is detected, the previous
 * topic's Q&A is labeled via LLM and cached to the database.
 */
@Service
public class TopicDetector {

    private static final Logger log = LoggerFactory.getLogger(TopicDetector.class);

    private final EmbeddingService embeddingService;
    private final ModelProvider llmProvider;
    private final TopicCacheRepository cacheRepo;

    @Value("${topic.push.similarity-threshold:0.4}")
    private double similarityThreshold;

    /** In-memory state: userId → last question embedding + text */
    private final ConcurrentHashMap<String, LastMessageState> lastState = new ConcurrentHashMap<>();

    public TopicDetector(EmbeddingService embeddingService,
                         TopicCacheRepository cacheRepo,
                         @Qualifier("deepSeekProvider") ModelProvider llmProvider) {
        this.embeddingService = embeddingService;
        this.cacheRepo = cacheRepo;
        this.llmProvider = llmProvider;
    }

    /**
     * Call this BEFORE processing the current question.
     * Checks if a topic shift occurred compared to the previous question.
     * If yes, the OLD topic is immediately cached (we have both Q&A from last round).
     */
    public void detectAndCache(String userId, String currentQuestion,
                               String conversationId) {
        log.info("TopicDetector: check user={} conv={} question(len={})",
            userId, conversationId, currentQuestion.length());
        float[] currentEmbedding = embeddingService.embed(currentQuestion);
        if (currentEmbedding == null || currentEmbedding.length == 0) return;

        LastMessageState prev = lastState.get(userId);

        if (prev != null && prev.answerText() != null) {
            double similarity = cosineSimilarity(currentEmbedding, prev.questionEmbedding());
            log.info("TopicDetector: similarity={} threshold={} user={} prevQ='{}'",
                similarity, similarityThreshold, userId,
                prev.questionText().length() > 30 ? prev.questionText().substring(0, 30) + "..." : prev.questionText());
            if (similarity < similarityThreshold) {
                // Topic shift detected — cache the PREVIOUS topic
                cacheOldTopic(userId, prev, conversationId);
            }
        }

        // Store current question state (answer will be patched later)
        lastState.put(userId, new LastMessageState(
            currentEmbedding, currentQuestion, null, Instant.now()
        ));
    }

    /**
     * Async variant of {@link #detectAndCache} — runs the (potentially slow)
     * embedding + topic labeling on the common fork-join pool so the chat
     * response is never blocked by topic-detection work.
     */
    public void detectAndCacheAsync(String userId, String currentQuestion,
                                    String conversationId) {
        CompletableFuture.runAsync(() -> {
            try {
                detectAndCache(userId, currentQuestion, conversationId);
            } catch (Exception e) {
                log.debug("TopicDetector: async detection failed (non-critical): {}", e.getMessage());
            }
        });
    }

    /**
     * Call this AFTER the LLM generates the answer.
     * Patches the answer into the last message state so the next shift check
     * can cache the full Q&A.
     */
    public void updateAnswer(String userId, String answer) {
        LastMessageState prev = lastState.get(userId);
        if (prev != null) {
            lastState.put(userId, new LastMessageState(
                prev.questionEmbedding(), prev.questionText(),
                answer, prev.timestamp()
            ));
        }
    }

    // ---------------------------------------------------------------
    // Private helpers
    // ---------------------------------------------------------------

    @Transactional
    private void cacheOldTopic(String userId, LastMessageState prev,
                               String conversationId) {
        String label = labelTopic(prev.questionText(), prev.answerText());
        TopicCache cache = new TopicCache(
            userId, label,
            "Q: " + prev.questionText() + "\nA: " + prev.answerText(),
            conversationId,
            LocalDateTime.ofInstant(prev.timestamp(), ZoneId.systemDefault())
        );
        cacheRepo.save(cache);
        log.info("TopicDetector: cached topic '{}' for user={} conv={}",
            label, userId, conversationId);
    }

    private String labelTopic(String question, String answer) {
        try {
            String prompt = "用1-3个词（中文）总结这段对话的核心话题，只输出话题名称，不要解释：\n" +
                "Q: " + question + "\nA: " + answer;
            return llmProvider.chat(prompt).trim();
        } catch (Exception e) {
            log.warn("TopicDetector: LLM labeling failed: {}", e.getMessage());
            // Fallback: use first 15 chars of question
            return question.length() > 15 ? question.substring(0, 15) + "..." : question;
        }
    }

    private static double cosineSimilarity(float[] a, float[] b) {
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom == 0 ? 0 : dot / denom;
    }

    /** Internal state record for tracking the last message per user. */
    private record LastMessageState(
        float[] questionEmbedding,
        String questionText,
        String answerText,
        Instant timestamp
    ) {}
}
