package org.example.educatorweb.profile.passive;

import org.example.educatorweb.profile.ProfileService;
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
        p.setKnowledgeBaseLevel("beginner");
        p.setKnowledgeBaseConfidence(new BigDecimal("0.50"));
        p.setCognitiveStyleType("visual");
        p.setCognitiveStyleConfidence(new BigDecimal("0.50"));
        p.setErrorPatternTags(new ArrayList<>());
        p.setErrorPatternConfidence(new BigDecimal("0.00"));
        p.setLearningPaceType("normal");
        p.setLearningPaceConfidence(new BigDecimal("0.50"));
        p.setContentPreferenceType("text");
        p.setContentPreferenceRatio(new LinkedHashMap<>());
        p.setGoalOrientationType("exam");
        p.setGoalOrientationConfidence(new BigDecimal("0.50"));
        return p;
    }

    /**
     * Normalize LLM output values to DB CHECK constraint enums.
     */
    private void sanitizeProfileForDb(StudentProfile p) {
        String kl = p.getKnowledgeBaseLevel();
        if (kl == null || kl.isBlank()
            || (!kl.equals("beginner") && !kl.equals("intermediate")
                && !kl.equals("advanced") && !kl.equals("master"))) {
            p.setKnowledgeBaseLevel("beginner");
        }

        String cs = p.getCognitiveStyleType();
        if (cs == null || cs.isBlank()
            || (!cs.equals("visual") && !cs.equals("auditory"))) {
            p.setCognitiveStyleType("visual");
        }

        String lp = p.getLearningPaceType();
        if (lp == null || lp.isBlank()
            || (!lp.equals("slow") && !lp.equals("normal") && !lp.equals("fast"))) {
            p.setLearningPaceType("normal");
        }

        String go = p.getGoalOrientationType();
        if (go == null || go.isBlank()
            || (!go.equals("exam") && !go.equals("research")
                && !go.equals("career") && !go.equals("interest"))) {
            p.setGoalOrientationType("exam");
        }

        Set<String> validTypes = Set.of("text", "video", "audio", "interactive", "graph", "ppt");
        String cp = p.getContentPreferenceType();
        if (cp == null || cp.isBlank() || !validTypes.contains(cp)) {
            p.setContentPreferenceType("text");
        }

        p.setKnowledgeBaseConfidence(clamp(p.getKnowledgeBaseConfidence()));
        p.setCognitiveStyleConfidence(clamp(p.getCognitiveStyleConfidence()));
        p.setErrorPatternConfidence(clamp(p.getErrorPatternConfidence()));
        p.setLearningPaceConfidence(clamp(p.getLearningPaceConfidence()));
        p.setGoalOrientationConfidence(clamp(p.getGoalOrientationConfidence()));
    }

    private BigDecimal clamp(BigDecimal bd) {
        if (bd == null) return new BigDecimal("0.50");
        if (bd.compareTo(BigDecimal.ZERO) < 0) return BigDecimal.ZERO;
        if (bd.compareTo(BigDecimal.ONE) > 0) return BigDecimal.ONE;
        return bd;
    }
}
