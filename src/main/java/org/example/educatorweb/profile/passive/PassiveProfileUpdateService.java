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
 * Runs asynchronously so chat response latency is unaffected.
 * Flow: check threshold → fetch unprocessed conversations → slice →
 * analyze each slice via Agent 1 → apply confidence adjustments →
 * persist → update Redis cursor.
 */
@Service
public class PassiveProfileUpdateService {

    private static final Logger log = LoggerFactory.getLogger(PassiveProfileUpdateService.class);

    private static final Set<String> VALID_PACE = Set.of("slow", "normal", "fast");
    private static final Set<String> VALID_GOAL = Set.of("exam", "research", "career", "interest");
    private static final Set<String> VALID_COGNITIVE = Set.of("visual", "auditory");
    private static final Set<String> VALID_KNOWLEDGE = Set.of("beginner", "intermediate", "advanced", "master");
    private static final Set<String> VALID_CONTENT = Set.of("text", "video", "audio", "interactive", "graph", "ppt");

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
     * Called asynchronously from {@code AiTutorServiceImpl.chat()}.
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
     * Normalize LLM output (Chinese labels) to DB CHECK constraint enums.
     * Step 1: Chinese → English mapping
     * Step 2: Fallback validation against CHECK constraints
     * Step 3: Clamp confidences to [0, 1]
     */
    private void sanitizeProfileForDb(StudentProfile p) {
        // ---- Step 1: Chinese → English mapping ----

        // knowledge: 入门/薄弱 → beginner, 一般 → intermediate, 熟练 → advanced, 优秀/专家 → master
        p.setKnowledgeBaseLevel(normalizeKnowledgeLevel(p.getKnowledgeBaseLevel()));

        // cognitive: 视觉型/直觉型 → visual, 言语型/分析型 → auditory
        String cs = p.getCognitiveStyleType();
        if (cs != null) {
            if (cs.contains("视觉") || cs.contains("直觉")) p.setCognitiveStyleType("visual");
            else if (cs.contains("言语") || cs.contains("分析")) p.setCognitiveStyleType("auditory");
        }

        // pace: 稳扎稳打型 → slow, 快速推进型/跳跃型 → fast
        String lp = p.getLearningPaceType();
        if (lp != null) {
            if (lp.contains("稳扎稳打")) p.setLearningPaceType("slow");
            else if (lp.contains("快速") || lp.contains("跳跃")) p.setLearningPaceType("fast");
        }

        // goal: 求职准备 → career, 学术深造 → research, 兴趣探索 → interest, 考证通关/课程考试 → exam
        String go = p.getGoalOrientationType();
        if (go != null) {
            if (go.contains("求职")) p.setGoalOrientationType("career");
            else if (go.contains("学术")) p.setGoalOrientationType("research");
            else if (go.contains("兴趣")) p.setGoalOrientationType("interest");
            else if (go.contains("考证") || go.contains("考试") || go.contains("exam")) p.setGoalOrientationType("exam");
        }

        // preference: 视频优先 → video, 文档优先 → text, 混合学习 → text
        String cp = p.getContentPreferenceType();
        if (cp != null) {
            if (cp.contains("视频")) p.setContentPreferenceType("video");
            else if (cp.contains("文档")) p.setContentPreferenceType("text");
            else if (cp.contains("混合")) p.setContentPreferenceType("text");
        }

        // ---- Step 2: Fallback validation (ensure enums match CHECK constraints) ----

        if (p.getKnowledgeBaseLevel() == null || !VALID_KNOWLEDGE.contains(p.getKnowledgeBaseLevel()))
            p.setKnowledgeBaseLevel("beginner");
        if (p.getCognitiveStyleType() == null || !VALID_COGNITIVE.contains(p.getCognitiveStyleType()))
            p.setCognitiveStyleType("visual");
        if (p.getLearningPaceType() == null || !VALID_PACE.contains(p.getLearningPaceType()))
            p.setLearningPaceType("normal");
        if (p.getGoalOrientationType() == null || !VALID_GOAL.contains(p.getGoalOrientationType()))
            p.setGoalOrientationType("exam");
        if (p.getContentPreferenceType() == null || !VALID_CONTENT.contains(p.getContentPreferenceType()))
            p.setContentPreferenceType("text");

        // Clean ratio keys to valid content types only
        Map<String, Double> ratio = p.getContentPreferenceRatio();
        if (ratio != null && !ratio.isEmpty()) {
            Map<String, Double> cleaned = new LinkedHashMap<>();
            for (var entry : ratio.entrySet()) {
                if (VALID_CONTENT.contains(entry.getKey())) {
                    cleaned.put(entry.getKey(), entry.getValue());
                }
            }
            p.setContentPreferenceRatio(cleaned.isEmpty() ? new LinkedHashMap<>() : cleaned);
        }

        // ---- Step 3: Clamp confidences to [0, 1] ----
        p.setKnowledgeBaseConfidence(clamp(p.getKnowledgeBaseConfidence()));
        p.setCognitiveStyleConfidence(clamp(p.getCognitiveStyleConfidence()));
        p.setErrorPatternConfidence(clamp(p.getErrorPatternConfidence()));
        p.setLearningPaceConfidence(clamp(p.getLearningPaceConfidence()));
        p.setGoalOrientationConfidence(clamp(p.getGoalOrientationConfidence()));
    }

    private String normalizeKnowledgeLevel(String llmOutput) {
        if (llmOutput == null || llmOutput.isBlank()) return "beginner";
        String s = llmOutput.trim();
        if (s.contains("入门") || s.contains("薄弱") || s.contains("beginner")) return "beginner";
        if (s.contains("一般") || s.contains("intermediate")) return "intermediate";
        if (s.contains("熟练") || s.contains("advanced")) return "advanced";
        if (s.contains("优秀") || s.contains("master") || s.contains("专家")) return "master";
        return "beginner";
    }

    private BigDecimal clamp(BigDecimal bd) {
        if (bd == null) return new BigDecimal("0.50");
        if (bd.compareTo(BigDecimal.ZERO) < 0) return BigDecimal.ZERO;
        if (bd.compareTo(BigDecimal.ONE) > 0) return BigDecimal.ONE;
        return bd;
    }
}
