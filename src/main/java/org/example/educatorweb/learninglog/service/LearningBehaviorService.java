package org.example.educatorweb.learninglog.service;

import org.example.educatorweb.learninglog.model.LearningBehaviorLog;
import org.example.educatorweb.learninglog.repository.LearningBehaviorLogRepository;
import org.example.educatorweb.learningpath.ProfileUpdatedEvent;
import org.example.educatorweb.topicpush.api.PushNotifyController;
import org.example.educatorweb.profile.model.StudentKnowledgeProficiency;
import org.example.educatorweb.profile.model.StudentKnowledgeProficiencyId;
import org.example.educatorweb.profile.model.StudentProfile;
import org.example.educatorweb.profile.repository.StudentKnowledgeProficiencyRepository;
import org.example.educatorweb.profile.repository.StudentProfileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Records learning behavior events and updates knowledge proficiency accordingly.
 *
 * <p>QUIZ_ANSWER → directly updates correct/total counts and recalculates proficiency.
 * RESOURCE_VIEW → updates lastStudyTime; ≥3 views on a concept nudges proficiency by +0.02.
 * CHAT_INTERACTION → updates lastStudyTime; ≥5 mentions nudges proficiency by +0.01.
 * KNOWLEDGE_BROWSE → log-only, no proficiency change.
 */
@Service
public class LearningBehaviorService {

    private static final Logger log = LoggerFactory.getLogger(LearningBehaviorService.class);

    // Event type constants
    public static final String RESOURCE_VIEW = "RESOURCE_VIEW";
    public static final String QUIZ_ANSWER = "QUIZ_ANSWER";
    public static final String CHAT_INTERACTION = "CHAT_INTERACTION";
    public static final String KNOWLEDGE_BROWSE = "KNOWLEDGE_BROWSE";

    private final LearningBehaviorLogRepository logRepo;
    private final StudentKnowledgeProficiencyRepository proficiencyRepo;
    private final StudentProfileRepository profileRepo;
    private final ApplicationEventPublisher eventPublisher;
    private final PushNotifyController pushNotifyController;

    public LearningBehaviorService(LearningBehaviorLogRepository logRepo,
                                   StudentKnowledgeProficiencyRepository proficiencyRepo,
                                   StudentProfileRepository profileRepo,
                                   ApplicationEventPublisher eventPublisher,
                                   PushNotifyController pushNotifyController) {
        this.logRepo = logRepo;
        this.proficiencyRepo = proficiencyRepo;
        this.profileRepo = profileRepo;
        this.eventPublisher = eventPublisher;
        this.pushNotifyController = pushNotifyController;
    }

    // ─── Public API ───────────────────────────────────────────

    @Transactional
    public void logResourceView(String userId, String concept, String resourceType, Long resourceId, String title) {
        String detail = "{\"resourceType\":\"" + resourceType + "\",\"resourceId\":" + resourceId
            + ",\"title\":\"" + escape(title) + "\"}";
        logRepo.save(new LearningBehaviorLog(userId, RESOURCE_VIEW, concept, detail));
        log.debug("LearningBehavior: RESOURCE_VIEW user={} concept={} type={}", userId, concept, resourceType);

        // Update lastStudyTime; ≥3 views → proficiency +0.02 (cap 0.95)
        touchProficiency(userId, concept);
        long viewCount = logRepo.countByUserIdAndEventTypeAndConcept(userId, RESOURCE_VIEW, concept);
        if (viewCount >= 3) {
            adjustProficiency(userId, concept, new BigDecimal("0.02"), new BigDecimal("0.95"));
        }
    }

    @Transactional
    public void logQuizAnswer(String userId, String concept, boolean correct) {
        String detail = "{\"correct\":" + correct + "}";
        logRepo.save(new LearningBehaviorLog(userId, QUIZ_ANSWER, concept, detail));
        log.debug("LearningBehavior: QUIZ_ANSWER user={} concept={} correct={}", userId, concept, correct);

        // Directly update proficiency
        StudentKnowledgeProficiency kp = getOrCreateProficiency(userId, concept);
        int total = kp.getTotalQuestions() + 1;
        int correctCount = kp.getCorrectQuestions() + (correct ? 1 : 0);
        kp.setTotalQuestions(total);
        kp.setCorrectQuestions(correctCount);
        kp.setLastStudyTime(LocalDateTime.now());
        kp.setProficiency(BigDecimal.valueOf(correctCount)
            .divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP));
        proficiencyRepo.save(kp);
    }

    @Transactional
    public void logQuizResults(String userId, String concept, int totalQuestions, int correctQuestions) {
        String detail = "{\"correct\":" + correctQuestions + ",\"total\":" + totalQuestions + "}";
        logRepo.save(new LearningBehaviorLog(userId, QUIZ_ANSWER, concept, detail));
        log.debug("LearningBehavior: QUIZ_ANSWER batch user={} concept={} correct={}/{}",
            userId, concept, correctQuestions, totalQuestions);

        // Batch update proficiency
        StudentKnowledgeProficiency kp = getOrCreateProficiency(userId, concept);
        kp.setTotalQuestions(kp.getTotalQuestions() + totalQuestions);
        kp.setCorrectQuestions(kp.getCorrectQuestions() + correctQuestions);
        kp.setLastStudyTime(LocalDateTime.now());
        int t = kp.getTotalQuestions();
        int c = kp.getCorrectQuestions();
        kp.setProficiency(t > 0
            ? BigDecimal.valueOf(c).divide(BigDecimal.valueOf(t), 4, RoundingMode.HALF_UP)
            : BigDecimal.ZERO);
        proficiencyRepo.save(kp);

        // Bidirectional feedback: quiz performance → profile adjustment
        maybeAdjustProfile(userId, concept, correctQuestions, totalQuestions);

        // Notify report subscribers to refresh
        try {
            pushNotifyController.notifyReportUpdated(userId);
        } catch (Exception e) {
            log.debug("LearningBehavior: report update notify skipped: {}", e.getMessage());
        }

        // Check proficiency milestone (>= 60%) and notify with next path node
        BigDecimal newProf = kp.getProficiency();
        if (newProf != null && newProf.doubleValue() >= 0.6) {
            String nextNode = null;
            try {
                var profileOpt = profileRepo.findById(userId);
                if (profileOpt.isPresent()) {
                    String pathJson = profileOpt.get().getLearningPathJson();
                    if (pathJson != null && !pathJson.isEmpty()) {
                        var path = new com.fasterxml.jackson.databind.ObjectMapper()
                            .readValue(pathJson, java.util.Map.class);
                        @SuppressWarnings("unchecked")
                        var nodes = (java.util.List<java.util.Map<String, Object>>) path.get("nodes");
                        if (nodes != null) {
                            for (int i = 0; i < nodes.size(); i++) {
                                String name = (String) nodes.get(i).get("knowledgePointName");
                                if (concept.equals(name) && i + 1 < nodes.size()) {
                                    nextNode = (String) nodes.get(i + 1).get("knowledgePointName");
                                    break;
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("LearningBehavior: milestone nextNode lookup failed: {}", e.getMessage());
            }
            int pct = (int) Math.round(newProf.doubleValue() * 100);
            pushNotifyController.notifyMilestone(userId, concept, pct, nextNode);
        }
    }

    @Transactional
    public void logChatInteraction(String userId, String concept, String question) {
        String escaped = question.length() > 150 ? escape(question.substring(0, 150)) + "..." : escape(question);
        String detail = "{\"question\":\"" + escaped + "\"}";
        logRepo.save(new LearningBehaviorLog(userId, CHAT_INTERACTION, concept, detail));
        log.debug("LearningBehavior: CHAT_INTERACTION user={} concept={}", userId, concept);

        // Update lastStudyTime; ≥5 interactions → proficiency +0.01 (cap 0.90)
        touchProficiency(userId, concept);
        long chatCount = logRepo.countByUserIdAndEventTypeAndConcept(userId, CHAT_INTERACTION, concept);
        if (chatCount >= 5) {
            adjustProficiency(userId, concept, new BigDecimal("0.01"), new BigDecimal("0.90"));
        }
    }

    @Transactional
    public void logKnowledgeBrowse(String userId, String concept) {
        logRepo.save(new LearningBehaviorLog(userId, KNOWLEDGE_BROWSE, concept, "{}"));
        log.debug("LearningBehavior: KNOWLEDGE_BROWSE user={} concept={}", userId, concept);
        // Log only — no proficiency change
    }

    // ─── Helpers ──────────────────────────────────────────────

    private StudentKnowledgeProficiency getOrCreateProficiency(String userId, String concept) {
        StudentKnowledgeProficiencyId id = new StudentKnowledgeProficiencyId(userId, concept);
        Optional<StudentKnowledgeProficiency> opt = proficiencyRepo.findById(id);
        if (opt.isPresent()) return opt.get();

        StudentKnowledgeProficiency kp = new StudentKnowledgeProficiency();
        kp.setStudentId(userId);
        kp.setConcept(concept);
        kp.setProficiency(BigDecimal.ZERO);
        kp.setTotalQuestions(0);
        kp.setCorrectQuestions(0);
        kp.setLastStudyTime(LocalDateTime.now());
        return proficiencyRepo.save(kp);
    }

    private void touchProficiency(String userId, String concept) {
        StudentKnowledgeProficiencyId id = new StudentKnowledgeProficiencyId(userId, concept);
        proficiencyRepo.findById(id).ifPresent(kp -> {
            kp.setLastStudyTime(LocalDateTime.now());
            proficiencyRepo.save(kp);
        });
    }

    private void adjustProficiency(String userId, String concept, BigDecimal delta, BigDecimal cap) {
        StudentKnowledgeProficiencyId id = new StudentKnowledgeProficiencyId(userId, concept);
        proficiencyRepo.findById(id).ifPresent(kp -> {
            BigDecimal current = kp.getProficiency() != null ? kp.getProficiency() : BigDecimal.ZERO;
            BigDecimal next = current.add(delta);
            if (next.compareTo(cap) > 0) next = cap;
            kp.setProficiency(next);
            kp.setLastStudyTime(LocalDateTime.now());
            proficiencyRepo.save(kp);
        });
    }

    // ─── Bidirectional feedback: behavior → profile ───────────

    /**
     * After a quiz submission, check if the student's proficiency has crossed
     * a threshold that warrants adjusting their six-dimension profile.
     */
    private void maybeAdjustProfile(String userId, String concept,
                                     int correct, int total) {
        if (total == 0) return;

        try {
            Optional<StudentProfile> opt = profileRepo.findById(userId);
            if (opt.isEmpty()) return;
            StudentProfile profile = opt.get();
            boolean changed = false;

            // D1 知识基础 — adjust based on overall proficiency average
            List<StudentKnowledgeProficiency> allKps = proficiencyRepo.findByStudentId(userId);
            if (allKps != null && !allKps.isEmpty()) {
                double avg = allKps.stream()
                    .filter(k -> k.getProficiency() != null)
                    .mapToDouble(k -> k.getProficiency().doubleValue())
                    .average().orElse(0.0);

                String currentD1 = profile.getKnowledgeBaseLevel();
                if (avg > 0.85 && "一般".equals(currentD1)) {
                    profile.setKnowledgeBaseLevel("扎实");
                    log.info("LearningBehavior: D1 upgraded to 扎实 for user={} (avg proficiency={})", userId, avg);
                    changed = true;
                } else if (avg > 0.7 && "薄弱".equals(currentD1)) {
                    profile.setKnowledgeBaseLevel("一般");
                    log.info("LearningBehavior: D1 upgraded to 一般 for user={} (avg proficiency={})", userId, avg);
                    changed = true;
                }
            }

            // D4 学习步调 — adjust based on recent quiz accuracy
            double accuracy = (double) correct / total;
            if (accuracy > 0.8 && "缓慢进度".equals(profile.getLearningPaceType())) {
                // Check if there are enough quiz records to be confident
                long quizCount = logRepo.countByUserIdAndEventTypeAndConcept(userId, QUIZ_ANSWER, concept);
                if (quizCount >= 3) {
                    profile.setLearningPaceType("正常进度");
                    log.info("LearningBehavior: D4 upgraded to 正常进度 for user={} (accuracy={})", userId, accuracy);
                    changed = true;
                }
            }

            // D5 内容偏好 — reinforce existing preference when user is active
            long totalResourceViews = logRepo.countByUserIdAndEventType(userId, RESOURCE_VIEW);
            if (totalResourceViews >= 8 && profile.getContentPreferenceRatio() != null) {
                // Bump each ratio slightly toward 1.0 (user is engaged with their preferred type)
                Map<String, Double> ratio = new java.util.HashMap<>(profile.getContentPreferenceRatio());
                String primary = profile.getContentPreferenceType();
                if (primary != null && ratio.containsKey(mapPreferenceKey(primary))) {
                    ratio.put(mapPreferenceKey(primary),
                        Math.min(1.0, ratio.get(mapPreferenceKey(primary)) + 0.05));
                    profile.setContentPreferenceRatio(ratio);
                    changed = true;
                }
                log.info("LearningBehavior: D5 ratio boosted for user={} ({} resource views)",
                    userId, totalResourceViews);
            }

            if (changed) {
                profile.setUpdatedAt(LocalDateTime.now());
                profileRepo.save(profile);
                // Fire event so LearningPathService can recalculate the path
                eventPublisher.publishEvent(new ProfileUpdatedEvent(
                    this, userId, profile.getKnowledgeBaseLevel()));
            }
        } catch (Exception e) {
            log.debug("LearningBehavior: profile adjustment skipped: {}", e.getMessage());
        }
    }

    /** Map preference type label to ratio key (e.g. "视频优先" → "video"). */
    private static String mapPreferenceKey(String type) {
        if (type == null) return "document";
        return switch (type) {
            case "视频优先" -> "video";
            case "文档优先" -> "document";
            case "混合学习" -> "mixed";
            case "实践学习" -> "code";
            default -> "document";
        };
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
