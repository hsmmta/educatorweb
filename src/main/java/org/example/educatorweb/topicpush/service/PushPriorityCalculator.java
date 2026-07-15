package org.example.educatorweb.topicpush.service;

import org.example.educatorweb.profile.ProfileService;
import org.example.educatorweb.profile.model.StudentKnowledgeProficiency;
import org.example.educatorweb.profile.model.StudentProfile;
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
    private final ProfileService profileService;

    public PushPriorityCalculator(StudentKnowledgeProficiencyRepository kpProficiencyRepo,
                                   ProfileService profileService) {
        this.kpProficiencyRepo = kpProficiencyRepo;
        this.profileService = profileService;
    }

    /**
     * Sort topics by priority (highest first).
     * @param includeWeakness if true (SCHEDULED push), prepend weakness topics
     */
    public List<PrioritizedTopic> prioritize(List<TopicCache> topics, String studentId,
                                              boolean includeWeakness) {
        Map<String, BigDecimal> proficiencyMap = buildProficiencyMap(studentId);
        ProfileMultipliers pm = loadProfileMultipliers(studentId);

        // Sort topics by composite score (lower = higher priority)
        List<PrioritizedTopic> sorted = IntStream.range(0, topics.size())
            .mapToObj(i -> {
                TopicCache t = topics.get(i);
                BigDecimal prof = proficiencyMap.getOrDefault(t.getTopicLabel(), null);
                return new PrioritizedTopic(t, prof, i, pm);
            })
            .sorted(Comparator.comparingDouble(PrioritizedTopic::score))
            .collect(Collectors.toList());

        if (includeWeakness) {
            List<PrioritizedTopic> weaknessTopics = buildWeaknessTopics(studentId, pm);
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

    private List<PrioritizedTopic> buildWeaknessTopics(String studentId, ProfileMultipliers pm) {
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
                return new PrioritizedTopic(synthetic, w.getProficiency(), -1, pm);
            })
            .collect(Collectors.toList());
    }

    /** Load six-dimension profile and convert to priority multipliers. */
    private ProfileMultipliers loadProfileMultipliers(String studentId) {
        try {
            StudentProfile p = profileService.getProfile(studentId);
            if (p == null) return ProfileMultipliers.DEFAULT;
            return new ProfileMultipliers(
                d1Multiplier(p.getKnowledgeBaseLevel()),   // D1: 知识基础
                d4Multiplier(p.getLearningPaceType()),      // D4: 学习步调
                d6Multiplier(p.getGoalOrientationType())    // D6: 目标导向
            );
        } catch (Exception e) {
            return ProfileMultipliers.DEFAULT;
        }
    }

    private static double d1Multiplier(String level) {
        if (level == null) return 1.0;
        return switch (level) {
            case "薄弱" -> 1.3;   // 基础差 → 薄弱话题权重高
            case "一般" -> 1.0;
            case "扎实" -> 0.8;   // 基础好 → 不需要那么紧急
            default -> 1.0;
        };
    }

    private static double d4Multiplier(String pace) {
        if (pace == null) return 1.0;
        return switch (pace) {
            case "缓慢进度" -> 1.2;  // 学得慢 → 少而精
            case "正常进度" -> 1.0;
            case "快速进度" -> 0.85;
            default -> 1.0;
        };
    }

    private static double d6Multiplier(String goal) {
        if (goal == null) return 1.0;
        return switch (goal) {
            case "应试准备" -> 1.15;  // 应试 → 高频考点优先
            case "求职准备" -> 1.05;
            case "学术深造" -> 1.0;
            default -> 1.0;
        };
    }

    /** Holds per-student multipliers from the six-dimension profile. */
    private record ProfileMultipliers(double d1, double d4, double d6) {
        static final ProfileMultipliers DEFAULT = new ProfileMultipliers(1.0, 1.0, 1.0);
    }

    /**
     * A topic with its priority metadata attached.
     */
    public static class PrioritizedTopic {
        private final TopicCache topic;
        private final int proficiencyRank;
        private final int recencyRank;
        private final ProfileMultipliers multipliers;

        PrioritizedTopic(TopicCache topic, BigDecimal proficiency, int originalIndex,
                          ProfileMultipliers multipliers) {
            this.topic = topic;
            this.proficiencyRank = proficiency != null
                ? (int) (proficiency.doubleValue() * 100) : 50;
            this.recencyRank = originalIndex >= 0 ? originalIndex + 1 : 1;
            this.multipliers = multipliers;
        }

        /**
         * Composite priority score. LOWER = HIGHER priority.
         * Base: proficiencyRank × 0.5 + recencyRank × 0.2
         * Adjusted by six-dimension multipliers:
         *   D1 (知识基础): 薄弱→1.3, 一般→1.0, 扎实→0.8
         *   D4 (学习步调): 缓慢→1.2, 正常→1.0, 快速→0.85
         *   D6 (目标导向): 应试→1.15, 求职→1.05, 学术→1.0
         */
        public double score() {
            double base = proficiencyRank * 0.5 + recencyRank * 0.2;
            return base * multipliers.d1() * multipliers.d4() * multipliers.d6();
        }

        public TopicCache topic() { return topic; }
        public String topicLabel() { return topic.getTopicLabel(); }
        public String qaText() { return topic.getQaText(); }
        public boolean isSynthetic() { return topic.getId() == null; }
    }
}
