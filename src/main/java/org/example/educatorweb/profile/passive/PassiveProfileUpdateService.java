package org.example.educatorweb.profile.passive;

import org.example.educatorweb.profile.ProfileService;
import org.example.educatorweb.profile.ProfileValueNormalizer;
import org.example.educatorweb.profile.model.StudentProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Orchestrates passive profile updates triggered after AI tutoring chat.
 *
 * <p>Runs asynchronously so chat response latency is unaffected.
 * Flow: check threshold → fetch unprocessed conversations → slice →
 * analyze each slice via Agent 1 → apply confidence adjustments →
 * persist → update Redis cursor.
 */
@Service
public class PassiveProfileUpdateService {

    private static final Logger log = LoggerFactory.getLogger(PassiveProfileUpdateService.class);


    private final ProcessedConversationTracker tracker;
    private final ConversationSlicer slicer;
    private final PassiveProfileUpdateAgent agent;
    private final ProfileService profileService;
    private final int threshold;

    public PassiveProfileUpdateService(ProcessedConversationTracker tracker,
                                        ConversationSlicer slicer,
                                        PassiveProfileUpdateAgent agent,
                                        ProfileService profileService,
                                        @Value("${profile.passive.threshold:8}") int threshold) {
        this.tracker = tracker;
        this.slicer = slicer;
        this.agent = agent;
        this.profileService = profileService;
        this.threshold = threshold;
    }

    /**
     * Check if the student has accumulated enough unprocessed conversations,
     * and if so, trigger a full passive profile update cycle.
     * <p>Called asynchronously from {@code AiTutorServiceImpl.chat()}.
     */
    @Async("profileUpdateExecutor")
    public void checkAndTrigger(String studentId) {
        try {
            // 1. Check threshold
            int count = tracker.countUnprocessed(studentId);
            if (count < threshold) {
                log.debug("PassiveProfileUpdate: student={} has {} unprocessed convs (threshold={}), skipping",
                    studentId, count, threshold);
                return;
            }

            log.info("PassiveProfileUpdate: triggering for student={}, unprocessedConvs={}",
                studentId, count);

            // 2. Fetch unprocessed conversations
            List<String> convIds = tracker.getUnprocessedConversationIds(studentId);
            if (convIds.isEmpty()) return;

            // 3. Slice
            List<ConversationSlicer.Slice> slices = slicer.slice(studentId, convIds);
            if (slices.isEmpty()) return;

            // 4. Load current profile (or create empty one)
            StudentProfile profile = profileService.getProfile(studentId);
            if (profile == null) {
                profile = createEmptyProfile(studentId);
            }

            // 5. Incremental update: each slice → LLM → confidence adjustment
            String maxTimestamp = null;
            for (ConversationSlicer.Slice slice : slices) {
                PassiveProfileUpdateAgent.UpdateResult result = agent.analyze(profile, slice);
                agent.applyConfidenceAdjustment(profile, result);

                if (maxTimestamp == null
                    || (slice.maxTimestamp() != null && slice.maxTimestamp().compareTo(maxTimestamp) > 0)) {
                    maxTimestamp = slice.maxTimestamp();
                }
            }

            // 6. Normalize to DB enum values
            sanitizeProfileForDb(profile);

            // 7. Persist
            profileService.updateProfile(studentId, profile);

            // 8. Mark as processed
            if (maxTimestamp != null && !maxTimestamp.isBlank()) {
                tracker.markProcessed(studentId, maxTimestamp);
            }

            log.info("PassiveProfileUpdate: completed for student={}, convs={}, slices={}, maxTs={}",
                studentId, convIds.size(), slices.size(), maxTimestamp);

        } catch (Exception e) {
            log.error("PassiveProfileUpdate: failed for student={}: {}", studentId, e.getMessage(), e);
        }
    }

    // ---- private helpers ----

    private StudentProfile createEmptyProfile(String studentId) {
        StudentProfile p = new StudentProfile();
        p.setStudentId(studentId);
        p.setKnowledgeBaseLevel("一般");
        p.setKnowledgeBaseConfidence(new BigDecimal("0.50"));
        p.setCognitiveStyleType("分析型");
        p.setCognitiveStyleConfidence(new BigDecimal("0.50"));
        p.setErrorPatternTags(new ArrayList<>());
        p.setErrorPatternConfidence(new BigDecimal("0.00"));
        p.setLearningPaceType("稳扎稳打型");
        p.setLearningPaceConfidence(new BigDecimal("0.50"));
        p.setContentPreferenceType("混合学习");
        p.setContentPreferenceRatio(new LinkedHashMap<>());
        p.setGoalOrientationType("兴趣探索");
        p.setGoalOrientationConfidence(new BigDecimal("0.50"));
        return p;
    }

    /**
     * Normalize LLM output (English → Chinese).
     * DB has no CHECK constraints; varchar(32) accepts any value.
     */
    private void sanitizeProfileForDb(StudentProfile p) {
        p.setKnowledgeBaseLevel(ProfileValueNormalizer.normalizeKnowledgeBase(p.getKnowledgeBaseLevel()));
        p.setCognitiveStyleType(ProfileValueNormalizer.normalizeCognitiveStyle(p.getCognitiveStyleType()));
        p.setErrorPatternTags(ProfileValueNormalizer.normalizeErrorTags(p.getErrorPatternTags()));
        p.setLearningPaceType(ProfileValueNormalizer.normalizeLearningPace(p.getLearningPaceType()));
        p.setContentPreferenceType(ProfileValueNormalizer.normalizeContentPreference(p.getContentPreferenceType()));
        p.setGoalOrientationType(ProfileValueNormalizer.normalizeGoalOrientation(p.getGoalOrientationType()));

        // Clamp confidences to [0, 1]
        p.setKnowledgeBaseConfidence(clamp(p.getKnowledgeBaseConfidence()));
        p.setCognitiveStyleConfidence(clamp(p.getCognitiveStyleConfidence()));
        p.setErrorPatternConfidence(clamp(p.getErrorPatternConfidence()));
        p.setLearningPaceConfidence(clamp(p.getLearningPaceConfidence()));
        p.setGoalOrientationConfidence(clamp(p.getGoalOrientationConfidence()));

        // Normalize ratio keys
        Map<String, Double> ratio = p.getContentPreferenceRatio();
        if (ratio != null && !ratio.isEmpty()) {
            Map<String, Double> cleaned = new LinkedHashMap<>();
            for (var entry : ratio.entrySet()) {
                String normalizedKey = ProfileValueNormalizer.normalizeContentPreference(entry.getKey());
                cleaned.put(normalizedKey, entry.getValue());
            }
            p.setContentPreferenceRatio(cleaned);
        }
    }

    private BigDecimal clamp(BigDecimal bd) {
        if (bd == null) return new BigDecimal("0.50");
        if (bd.compareTo(BigDecimal.ZERO) < 0) return BigDecimal.ZERO;
        if (bd.compareTo(BigDecimal.ONE) > 0) return BigDecimal.ONE;
        return bd;
    }
}
