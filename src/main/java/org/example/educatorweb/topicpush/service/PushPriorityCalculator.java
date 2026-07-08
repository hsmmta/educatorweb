package org.example.educatorweb.topicpush.service;

import org.example.educatorweb.profile.model.StudentKnowledgeProficiency;
import org.example.educatorweb.profile.repository.StudentKnowledgeProficiencyRepository;
import org.example.educatorweb.topicpush.model.TopicCache;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Calculates push priority for cached topics.
 *
 * Rules:
 * - proficiency_rank: lower proficiency → higher priority (rank = 1 for lowest proficiency)
 * - recency_rank: older ended_at → higher priority (rank = 1 for oldest)
 * - Composite score: score = proficiency_rank * 0.7 + recency_rank * 0.3
 * - Lower score = higher priority.
 *
 * For scheduled push, weakness topics are prepended (highest priority).
 */
@Service
public class PushPriorityCalculator {

    private final StudentKnowledgeProficiencyRepository kpProficiencyRepo;

    public PushPriorityCalculator(StudentKnowledgeProficiencyRepository kpProficiencyRepo) {
        this.kpProficiencyRepo = kpProficiencyRepo;
    }

    /**
     * Sort topics by priority (highest first).
     * @param includeWeakness if true (SCHEDULED push), prepend weakness topics
     */
    public List<PrioritizedTopic> prioritize(List<TopicCache> topics, String studentId,
                                              boolean includeWeakness) {
        Map<String, BigDecimal> proficiencyMap = buildProficiencyMap(studentId);

        // Sort topics by composite score (lower = higher priority)
        List<PrioritizedTopic> sorted = IntStream.range(0, topics.size())
            .mapToObj(i -> {
                TopicCache t = topics.get(i);
                BigDecimal prof = proficiencyMap.getOrDefault(t.getTopicLabel(), null);
                return new PrioritizedTopic(t, prof, i);
            })
            .sorted(Comparator.comparingDouble(PrioritizedTopic::score))
            .collect(Collectors.toList());

        if (includeWeakness) {
            // Prepend weakness topics at the very top (buildWeaknessTopics handles empty data)
            List<PrioritizedTopic> weaknessTopics = buildWeaknessTopics(studentId);
            weaknessTopics.addAll(sorted);
            return weaknessTopics;
        }
        return sorted;
    }

    // ---------------------------------------------------------------
    // Private helpers
    // ---------------------------------------------------------------

    private Map<String, BigDecimal> buildProficiencyMap(String studentId) {
        List<StudentKnowledgeProficiency> details = kpProficiencyRepo.findByStudentId(studentId);
        if (details == null || details.isEmpty()) return Map.of();
        return details.stream()
            .collect(Collectors.toMap(
                StudentKnowledgeProficiency::getConcept,
                StudentKnowledgeProficiency::getProficiency,
                (a, b) -> a
            ));
    }

    private List<PrioritizedTopic> buildWeaknessTopics(String studentId) {
        List<StudentKnowledgeProficiency> details = kpProficiencyRepo.findByStudentId(studentId);
        if (details == null) return List.of();
        return details.stream()
            .filter(d -> d.getProficiency() != null
                && d.getProficiency().compareTo(new BigDecimal("0.6")) < 0)
            .sorted(Comparator.comparing(d -> d.getProficiency() != null
                ? d.getProficiency() : BigDecimal.ONE))
            .limit(3)
            .map(w -> {
                TopicCache synthetic = new TopicCache(
                    studentId,
                    w.getConcept(),
                    "薄弱点: " + w.getConcept() + " (熟练度: " + w.getProficiency() + ")",
                    null,
                    w.getLastStudyTime() != null
                        ? w.getLastStudyTime()
                        : java.time.LocalDateTime.now().minusDays(7)
                );
                return new PrioritizedTopic(synthetic, w.getProficiency(), -1);
            })
            .collect(Collectors.toList());
    }

    /**
     * A topic with its priority metadata attached.
     */
    public static class PrioritizedTopic {
        private final TopicCache topic;
        private final int proficiencyRank;  // lower number = lower proficiency = weaker
        private final int recencyRank;      // lower number = older = higher priority

        PrioritizedTopic(TopicCache topic, BigDecimal proficiency, int originalIndex) {
            this.topic = topic;
            // proficiencyRank: lower proficiency value = higher rank number (we want inverse for scoring)
            // So a proficiency of 0.1 (very weak) → rank ≈ 10, proficiency of 0.9 (strong) → rank ≈ 90
            this.proficiencyRank = proficiency != null
                ? (int) (proficiency.doubleValue() * 100)
                : 50;  // middle rank for unmatched topics
            this.recencyRank = originalIndex >= 0 ? originalIndex + 1 : 1;
        }

        /**
         * Composite priority score. LOWER = HIGHER priority.
         * proficiency_rank × 0.7 + recency_rank × 0.3
         *
         * Lower proficiency → smaller rank value → smaller score → higher priority
         * Older topic → smaller recency rank → smaller score → higher priority
         */
        public double score() {
            return proficiencyRank * 0.7 + recencyRank * 0.3;
        }

        public TopicCache topic() { return topic; }
        public String topicLabel() { return topic.getTopicLabel(); }
        public String qaText() { return topic.getQaText(); }
        public boolean isSynthetic() { return topic.getId() == null; }
    }
}
