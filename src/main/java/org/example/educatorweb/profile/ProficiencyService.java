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
import java.util.ArrayList;
import java.util.List;

/**
 * 知识点掌握度服务。
 * 管理知识点粒度的 proficiency（掌握度）和 confidence（置信度）。
 *
 * <p>proficiency = correctQuestions / totalQuestions（实时计算，0~1）
 * <p>confidence = 1 - e^(-k * totalQuestions)（函数计算，快速上升后渐近于1）
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
     *
     * @param studentId      学生ID
     * @param concept         知识点名称（中文名，与 Neo4j KnowledgePoint.name 对应）
     * @param correct         本次答题是否正确
     * @return 更新后的掌握度结果
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

        // proficiency = correct / total
        BigDecimal proficiency = BigDecimal.ZERO;
        if (prof.getTotalQuestions() > 0) {
            proficiency = BigDecimal.valueOf(prof.getCorrectQuestions())
                .divide(BigDecimal.valueOf(prof.getTotalQuestions()), 4, RoundingMode.HALF_UP);
        }
        prof.setProficiency(proficiency);
        prof.setLastStudyTime(LocalDateTime.now());

        proficiencyRepo.save(prof);

        double confidence = confidence(prof.getTotalQuestions());

        log.info("ProficiencyService: student={}, concept={}, proficiency={}, confidence={:.4f}, total={}, correct={}",
            studentId, concept, proficiency, confidence, prof.getTotalQuestions(), prof.getCorrectQuestions());

        // 同步到 StudentProfile.knowledgeDetails
        syncToProfile(studentId, prof);

        return new ProficiencyResult(concept, proficiency.doubleValue(), confidence,
            prof.getTotalQuestions(), prof.getCorrectQuestions());
    }

    /**
     * 计算置信度。不存数据库，每次实时计算。
     * confidence(n) = 1 - e^(-k * n)，k = 0.4
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
     * 获取学生在指定知识点上的掌握度。如果从未答过题，返回默认值（proficiency=0, confidence=0）。
     */
    @Transactional(readOnly = true)
    public ProficiencyResult getProficiency(String studentId, String concept) {
        StudentKnowledgeProficiencyId id = new StudentKnowledgeProficiencyId(studentId, concept);
        return proficiencyRepo.findById(id)
            .map(p -> new ProficiencyResult(concept,
                p.getProficiency() != null ? p.getProficiency().doubleValue() : 0.0,
                confidence(p.getTotalQuestions()),
                p.getTotalQuestions(), p.getCorrectQuestions()))
            .orElse(new ProficiencyResult(concept, 0.0, 0.0, 0, 0));
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

    // ============ 数据类型 ============

    /** 单次答题结果 */
    public record AnswerResult(int questionIndex, boolean correct, String relatedConcept) {}

    /** 掌握度更新结果 */
    public record ProficiencyResult(String concept, double proficiency, double confidence,
                                     int totalQuestions, int correctQuestions) {}
}
