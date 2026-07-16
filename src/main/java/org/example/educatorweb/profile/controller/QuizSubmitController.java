package org.example.educatorweb.profile.controller;

import org.example.educatorweb.dto.ResponseResult;
import org.example.educatorweb.knowledgegraph.KnowledgeGraphService;
import org.example.educatorweb.knowledgegraph.model.KnowledgeContext;
import org.example.educatorweb.profile.ProficiencyService;
import org.example.educatorweb.profile.ProficiencyService.AnswerResult;
import org.example.educatorweb.profile.ProficiencyService.ProficiencyResult;
import org.example.educatorweb.profile.ProfileUpdateTrigger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 题库提交控制器。
 * 接收前端答题结果，更新知识点掌握度，必要时触发画像更新。
 *
 * <pre>
 * POST /api/quiz/submit
 * {
 *   "studentId": "xxx",
 *   "knowledgePoint": "支持向量机",
 *   "results": [
 *     { "questionIndex": 0, "correct": true, "relatedConcept": "SVM" },
 *     { "questionIndex": 1, "correct": false, "relatedConcept": "核函数" }
 *   ]
 * }
 * </pre>
 */
@RestController
@RequestMapping("/api/quiz")
public class QuizSubmitController {

    private static final Logger log = LoggerFactory.getLogger(QuizSubmitController.class);

    private final ProficiencyService proficiencyService;
    private final ProfileUpdateTrigger profileUpdateTrigger;
    private final KnowledgeGraphService kgService;

    public QuizSubmitController(ProficiencyService proficiencyService,
                                 ProfileUpdateTrigger profileUpdateTrigger,
                                 KnowledgeGraphService kgService) {
        this.proficiencyService = proficiencyService;
        this.profileUpdateTrigger = profileUpdateTrigger;
        this.kgService = kgService;
    }

    /**
     * 提交答题结果，更新知识点掌握度。
     */
    @PostMapping("/submit")
    public ResponseResult<Map<String, Object>> submit(@RequestBody QuizSubmitRequest request) {
        String studentId = request.studentId();
        String knowledgePoint = request.knowledgePoint();

        List<AnswerResult> results = request.results() != null ? request.results() : List.of();
        log.info("QuizSubmit: student={}, topic={}, results={}",
            studentId, knowledgePoint, results.size());

        // Record proficiency for ALL quiz answers (no Neo4j guard — proficiency is concept-agnostic)
        for (AnswerResult r : results) {
            String concept = (r.relatedConcept() != null && !r.relatedConcept().isBlank())
                ? r.relatedConcept() : knowledgePoint;
            try {
                proficiencyService.recordAnswer(studentId, concept, r.correct());
            } catch (Exception e) {
                log.warn("QuizSubmit: proficiency record failed for {}: {}", concept, e.getMessage());
            }
        }

        // 2. 重新获取所有更新后的结果用于返回
        List<ProficiencyResult> allResults = results.stream()
            .map(r -> {
                String concept = (r.relatedConcept() != null && !r.relatedConcept().isBlank())
                    ? r.relatedConcept() : knowledgePoint;
                return proficiencyService.getProficiency(studentId, concept);
            })
            .toList();

        // 3. 检查是否需要触发画像更新（答题也算一轮对话交互）
        boolean profileUpdated = profileUpdateTrigger.onInteraction(studentId);

        return ResponseResult.success(Map.of(
            "proficiencyUpdates", (Object) allResults,
            "profileUpdated", profileUpdated
        ));
    }

    /**
     * 获取学生在指定知识点上的掌握度。
     * GET /api/quiz/proficiency/{studentId}?concept=支持向量机
     */
    @GetMapping("/proficiency/{studentId}")
    public ResponseResult<ProficiencyResult> getProficiency(
            @PathVariable String studentId,
            @RequestParam String concept) {
        ProficiencyResult result = proficiencyService.getProficiency(studentId, concept);
        return ResponseResult.success(result);
    }

    /**
     * 检查知识点是否存在于 Neo4j 知识图谱中。
     */
    private boolean isInKnowledgeGraph(String conceptName) {
        try {
            KnowledgeContext ctx = kgService.queryContext(conceptName);
            // 如果返回了非空的 context（有 difficulty > 0 或有前后置知识），说明在 KG 中
            return ctx != null && (ctx.difficultyLevel() > 0
                || !ctx.prerequisites().isEmpty()
                || !ctx.successors().isEmpty()
                || !ctx.relatedConcepts().isEmpty());
        } catch (Exception e) {
            log.debug("QuizSubmit: KG check failed for '{}': {}", conceptName, e.getMessage());
            return false;
        }
    }

    /**
     * 答题提交请求体。
     */
    public record QuizSubmitRequest(
        String studentId,
        String knowledgePoint,
        List<AnswerResult> results
    ) {}
}
