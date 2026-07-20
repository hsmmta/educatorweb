package org.example.educatorweb.profile;

import org.example.educatorweb.profile.model.StudentKnowledgeProficiency;
import org.example.educatorweb.profile.model.StudentKnowledgeProficiencyId;
import org.example.educatorweb.profile.model.StudentProfile;
import org.example.educatorweb.profile.repository.StudentKnowledgeProficiencyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * 知识点掌握度服务。
 * 管理知识点粒度的 rawProficiency（原始正确率）、effectiveProficiency（艾宾浩斯衰减后）
 * 和 confidence（置信度）。
 *
 *rawProficiency = correctQuestions / totalQuestions — 存储在 DB，每次答题后更新
 *effectiveProficiency = rawProficiency × retention — 反映遗忘后的当前真实水平
 *confidence = 1 - e^(-k × totalQuestions) — 基于答题数量的可信度，不受时间影响
 *
 * retention = e^(-daysSinceLastStudy / halfLife)
 * halfLife 根据原始掌握度分级设定，符合"掌握越牢固遗忘越慢"的记忆规律：
 * <pre>
 *   rawProficiency ≥ 0.8  → halfLife = 30天
 *   0.6 ≤ raw < 0.8       → halfLife = 21天
 *   0.4 ≤ raw < 0.6       → halfLife = 14天
 *   0.2 ≤ raw < 0.4       → halfLife = 7天
 *   raw < 0.2             → halfLife = 3天
 * </pre>
 * 每次重新答题/学习后，lastStudyTime 更新，衰减重置。
 */
@Service
public class ProficiencyService {

    private static final Logger log = LoggerFactory.getLogger(ProficiencyService.class);

    /** 置信度衰减系数：k=0.4 使得第1题≈0.33, 第5题≈0.86, 第10题≈0.98 */
    private static final double CONFIDENCE_K = 0.4;

    private final StudentKnowledgeProficiencyRepository proficiencyRepo;
    private final ProfileService profileService;

    public ProficiencyService(StudentKnowledgeProficiencyRepository proficiencyRepo,
                              ProfileService profileService) {
        this.proficiencyRepo = proficiencyRepo;
        this.profileService = profileService;
    }

    /**
     * 根据单次答题结果更新知识点掌握度。
     * 答对/答错均更新 lastStudyTime，重置遗忘衰减。
     */
    @Transactional
    public ProficiencyResult recordAnswer(String studentId, String concept, boolean correct) {
        return updateProficiency(studentId, concept, correct ? 1 : 0, 1);
    }

    /**
     * 批量更新知识点掌握度。
     */
    @Transactional
    public List<ProficiencyResult> recordAnswers(String studentId, String knowledgePoint,
                                                  List<AnswerResult> results) {
        List<ProficiencyResult> outcomes = new ArrayList<>();
        for (AnswerResult r : results) {
            String concept = (r.relatedConcept() != null && !r.relatedConcept().isBlank())
                ? r.relatedConcept() : knowledgePoint;
            outcomes.add(updateProficiency(studentId, concept,
                r.correct() ? 1 : 0, 1));
        }
        return outcomes;
    }

    private ProficiencyResult updateProficiency(String studentId, String concept,
                                                 int addCorrect, int addTotal) {
        StudentKnowledgeProficiencyId id = new StudentKnowledgeProficiencyId(studentId, concept);
        StudentKnowledgeProficiency prof = proficiencyRepo.findById(id).orElseGet(() -> {
            StudentKnowledgeProficiency p = new StudentKnowledgeProficiency();
            p.setStudentId(studentId);
            p.setConcept(concept);
            p.setTotalQuestions(0);
            p.setCorrectQuestions(0);
            p.setProficiency(BigDecimal.ZERO);
            return p;
        });

        prof.setTotalQuestions(prof.getTotalQuestions() + addTotal);
        prof.setCorrectQuestions(prof.getCorrectQuestions() + addCorrect);

        // rawProficiency = correct / total
        BigDecimal rawProficiency = BigDecimal.ZERO;
        if (prof.getTotalQuestions() > 0) {
            rawProficiency = BigDecimal.valueOf(prof.getCorrectQuestions())
                .divide(BigDecimal.valueOf(prof.getTotalQuestions()), 4, RoundingMode.HALF_UP);
        }
        prof.setProficiency(rawProficiency);

        // 答题行为重置遗忘时钟
        prof.setLastStudyTime(LocalDateTime.now());

        proficiencyRepo.save(prof);

        double raw = rawProficiency.doubleValue();
        double effective = effectiveProficiency(raw, prof.getLastStudyTime());
        double conf = confidence(prof.getTotalQuestions());

        log.info("ProficiencyService: student={}, concept={}, raw={:.4f}, effective={:.4f}, confidence={:.4f}, total={}, correct={}",
            studentId, concept, raw, effective, conf, prof.getTotalQuestions(), prof.getCorrectQuestions());

        syncToProfile(studentId, prof);

        return buildResult(concept, raw, prof.getTotalQuestions(), prof.getCorrectQuestions(),
            prof.getLastStudyTime());
    }

    /**
     * 获取学生在指定知识点上的有效掌握度（含遗忘衰减）。
     * 从未答过题返回 proficiency=0, confidence=0。
     */
    @Transactional(readOnly = true)
    public ProficiencyResult getProficiency(String studentId, String concept) {
        StudentKnowledgeProficiencyId id = new StudentKnowledgeProficiencyId(studentId, concept);
        return proficiencyRepo.findById(id)
            .map(p -> buildResult(concept,
                p.getProficiency() != null ? p.getProficiency().doubleValue() : 0.0,
                p.getTotalQuestions(), p.getCorrectQuestions(), p.getLastStudyTime()))
            .orElse(new ProficiencyResult(concept, 0.0, 0.0, 0.0, 0, 0, null, 0));
    }

    /**
     * 获取学生在所有已练习知识点上的有效掌握度列表。
     */
    @Transactional(readOnly = true)
    public List<ProficiencyResult> getAllProficiencies(String studentId) {
        return proficiencyRepo.findByStudentId(studentId).stream()
            .map(p -> buildResult(p.getConcept(),
                p.getProficiency() != null ? p.getProficiency().doubleValue() : 0.0,
                p.getTotalQuestions(), p.getCorrectQuestions(), p.getLastStudyTime()))
            .toList();
    }

    private ProficiencyResult buildResult(String concept, double rawProficiency,
                                           int totalQuestions, int correctQuestions,
                                           LocalDateTime lastStudyTime) {
        double effective = effectiveProficiency(rawProficiency, lastStudyTime);
        double conf = confidence(totalQuestions);
        long daysSinceStudy = lastStudyTime != null
            ? ChronoUnit.DAYS.between(lastStudyTime, LocalDateTime.now()) : 0;
        return new ProficiencyResult(concept, rawProficiency, effective, conf,
            totalQuestions, correctQuestions, lastStudyTime, daysSinceStudy);
    }

    /**
     * 计算置信度。不存数据库，每次基于答题总数实时计算。
     * confidence(n) = 1 - e^(-k × n)，k = 0.4
     *
     * <pre>
     * n=1  → 0.33     n=3  → 0.70     n=5  → 0.86
     * n=7  → 0.94     n=10 → 0.98     n=15 → 0.998
     * </pre>
     */
    public static double confidence(int totalQuestions) {
        if (totalQuestions <= 0) return 0.0;
        return 1.0 - Math.exp(-CONFIDENCE_K * totalQuestions);
    }

    /**
     * 应用艾宾浩斯遗忘衰减，计算有效掌握度。
     *
     * effectiveProficiency = rawProficiency × e^(-daysSince / halfLife)
     *
     * halfLife 分级：
     * <pre>
     *   raw ≥ 0.8  → 30天（牢固，慢遗忘）
     *   raw ≥ 0.6  → 21天
     *   raw ≥ 0.4  → 14天
     *   raw ≥ 0.2  →  7天
     *   raw < 0.2  →  3天（薄弱，快遗忘）
     * </pre>
     *
     * @param rawProficiency 原始正确率 (0~1)
     * @param lastStudyTime  最近学习时间，null 表示从未学习
     * @return 衰减后的有效掌握度 (0~1)
     */
    public static double effectiveProficiency(double rawProficiency, LocalDateTime lastStudyTime) {
        if (lastStudyTime == null) return rawProficiency;
        if (rawProficiency <= 0) return 0;

        long daysSince = Math.max(0, ChronoUnit.DAYS.between(lastStudyTime, LocalDateTime.now()));
        double halfLife = estimateHalfLife(rawProficiency);
        double retention = Math.exp(-daysSince / halfLife);

        return rawProficiency * retention;
    }

    /**
     * 根据原始掌握度估计记忆半衰期。
     * 掌握越牢固 → 半衰期越长 → 遗忘越慢。
     */
    private static double estimateHalfLife(double rawProficiency) {
        if (rawProficiency >= 0.8) return 30.0;
        if (rawProficiency >= 0.6) return 21.0;
        if (rawProficiency >= 0.4) return 14.0;
        if (rawProficiency >= 0.2) return 7.0;
        return 3.0;
    }

    private void syncToProfile(String studentId, StudentKnowledgeProficiency prof) {
        try {
            StudentProfile profile = profileService.getProfile(studentId);
            if (profile != null) {
                List<StudentKnowledgeProficiency> details = profile.getKnowledgeDetails();
                if (details == null) {
                    details = new ArrayList<>();
                    profile.setKnowledgeDetails(details);
                }
                boolean found = false;
                for (int i = 0; i < details.size(); i++) {
                    if (details.get(i).getConcept().equals(prof.getConcept())) {
                        details.set(i, prof);
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    prof.setStudentProfile(profile);
                    details.add(prof);
                }
                profileService.updateProfile(studentId, profile);
            }
        } catch (Exception e) {
            log.warn("ProficiencyService: failed to sync to profile: {}", e.getMessage());
        }
    }

    /** 单次答题结果 */
    public record AnswerResult(int questionIndex, boolean correct, String relatedConcept) {}

    /**
     * 掌握度结果（三维评估）。
     *
     * @param concept           知识点名称
     * @param rawProficiency    原始正确率 (correct/total)，存储在 DB
     * @param effectiveProficiency 经艾宾浩斯衰减后的有效掌握度
     * @param confidence        置信度 (1-e^(-kn))，基于答题数
     * @param totalQuestions    总答题数
     * @param correctQuestions  正确答题数
     * @param lastStudyTime     最近学习时间
     * @param daysSinceStudy    距上次学习天数
     */
    public record ProficiencyResult(
        String concept,
        double rawProficiency,
        double effectiveProficiency,
        double confidence,
        int totalQuestions,
        int correctQuestions,
        LocalDateTime lastStudyTime,
        long daysSinceStudy
    ) {}
}
