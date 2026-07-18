package org.example.educatorweb.profile.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import org.example.educatorweb.dto.ResponseResult;
import org.example.educatorweb.learninglog.model.WrongAnswer;
import org.example.educatorweb.learninglog.repository.WrongAnswerRepository;
import org.example.educatorweb.profile.agent.ErrorPatternAgent;
import org.example.educatorweb.knowledgegraph.KnowledgeGraphService;
import org.example.educatorweb.knowledgegraph.model.KnowledgeContext;
import org.example.educatorweb.profile.ProficiencyService;
import org.example.educatorweb.profile.ProficiencyService.AnswerResult;
import org.example.educatorweb.profile.ProficiencyService.ProficiencyResult;
import org.example.educatorweb.profile.ProfileService;
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
    private final ProfileService profileService;
    private final OpenAiChatModel chatModel;
    private final WrongAnswerRepository wrongAnswerRepo;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public QuizSubmitController(ProficiencyService proficiencyService,
                                 ProfileUpdateTrigger profileUpdateTrigger,
                                 KnowledgeGraphService kgService,
                                 ProfileService profileService,
                                 OpenAiChatModel chatModel,
                                 WrongAnswerRepository wrongAnswerRepo) {
        this.proficiencyService = proficiencyService;
        this.profileUpdateTrigger = profileUpdateTrigger;
        this.kgService = kgService;
        this.profileService = profileService;
        this.chatModel = chatModel;
        this.wrongAnswerRepo = wrongAnswerRepo;
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

        // Record proficiency: always under the main topic (for path node matching)
        // AND under the specific sub-concept (for fine-grained tracking)
        for (AnswerResult r : results) {
            try {
                // Primary: record under the broad topic so path nodes can find it
                proficiencyService.recordAnswer(studentId, knowledgePoint, r.correct());
                // Secondary: also record under the specific sub-concept if different
                String subConcept = r.relatedConcept();
                if (subConcept != null && !subConcept.isBlank()
                    && !subConcept.equals(knowledgePoint)) {
                    proficiencyService.recordAnswer(studentId, subConcept, r.correct());
                }
            } catch (Exception e) {
                log.warn("QuizSubmit: proficiency record failed: {}", e.getMessage());
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

        // 3. 从答题数据中分析易错偏好，更新画像
        updateErrorPatterns(studentId);

        // 4. 检查是否需要触发画像更新（答题也算一轮对话交互）
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
     * 用 LLM 分析薄弱知识点，归因为认知错误类型（概念混淆、基础薄弱、过度泛化等）。
     * 输入：掌握度 < 0.6 的概念 + 答题统计
     * 输出：认知错误标签数组，如 ["概念混淆", "基础薄弱"]
     */
    private void updateErrorPatterns(String studentId) {
        try {
            List<ProficiencyResult> all = proficiencyService.getAllProficiencies(studentId);
            if (all.isEmpty()) return;

            // Collect weak concepts with stats for LLM analysis
            List<ProficiencyResult> weakOnes = all.stream()
                .filter(p -> p.effectiveProficiency() < 0.6 && p.confidence() >= 0.3)
                .sorted(java.util.Comparator.comparingDouble(ProficiencyResult::effectiveProficiency))
                .limit(8)
                .toList();

            if (weakOnes.isEmpty()) {
                if (all.size() > 5) {
                    profileService.updateErrorPatterns(studentId,
                        java.util.List.of("无明显薄弱点"), new java.math.BigDecimal("0.60"));
                }
                return;
            }

            // Build weak points description for LLM agent
            StringBuilder weakDesc = new StringBuilder();
            for (ProficiencyResult w : weakOnes) {
                weakDesc.append(String.format("- %s：正确率 %.0f%%，共 %d 题，正确 %d 题\n",
                    w.concept(), w.rawProficiency() * 100, w.totalQuestions(), w.correctQuestions()));
            }

            ErrorPatternAgent agent = AiServices.create(ErrorPatternAgent.class, chatModel);
            String response = agent.analyze(weakDesc.toString());
            log.info("QuizSubmit: LLM error pattern response: {}", response);

            // Parse LLM response — extract JSON array
            String json = response.trim();
            if (json.startsWith("```")) {
                json = json.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();
            }
            // Find the [ ... ] part
            int start = json.indexOf('[');
            int end = json.lastIndexOf(']');
            if (start >= 0 && end > start) {
                json = json.substring(start, end + 1);
            }

            @SuppressWarnings("unchecked")
            List<String> tags = objectMapper.readValue(json, List.class);
            if (tags != null && !tags.isEmpty()) {
                double conf = Math.min(0.85, 0.40 + tags.size() * 0.12);
                // Use targeted update — avoids LazyInitializationException on knowledgeDetails
                profileService.updateErrorPatterns(studentId, tags,
                    new java.math.BigDecimal(String.format("%.2f", conf)));
                log.info("QuizSubmit: error patterns updated for student={}, tags={}", studentId, tags);
            }
        } catch (Exception e) {
            log.warn("QuizSubmit: error pattern LLM analysis failed: {}", e.getMessage());
        }
    }

    /**
     * Save a wrong answer to the wrong answer collection.
     * POST /api/quiz/wrong-answer
     */
    @PostMapping("/wrong-answer")
    public ResponseResult<Map<String, Object>> saveWrongAnswer(@RequestBody WrongAnswerRequest request) {
        try {
            String optionsJson = "[]";
            if (request.options() != null) {
                try {
                    optionsJson = objectMapper.writeValueAsString(request.options());
                } catch (Exception e) {
                    log.warn("QuizSubmit: failed to serialize options: {}", e.getMessage());
                }
            }
            WrongAnswer wa = new WrongAnswer(
                request.studentId(), request.question(), optionsJson,
                request.userAnswer(), request.correctAnswer(),
                request.knowledgePoint(), request.quizTitle()
            );
            wrongAnswerRepo.save(wa);
            log.info("QuizSubmit: wrong answer saved for student={}, id={}", request.studentId(), wa.getId());
            return ResponseResult.success(Map.of("id", wa.getId()));
        } catch (Exception e) {
            log.error("QuizSubmit: failed to save wrong answer: {}", e.getMessage());
            return ResponseResult.error("保存错题失败");
        }
    }

    /**
     * Get all wrong answers for a student, newest first.
     * GET /api/quiz/wrong-answers/{studentId}
     */
    @GetMapping("/wrong-answers/{studentId}")
    public ResponseResult<List<Map<String, Object>>> getWrongAnswers(@PathVariable String studentId) {
        List<WrongAnswer> list = wrongAnswerRepo.findByStudentIdOrderByCreatedAtDesc(studentId);
        List<Map<String, Object>> result = list.stream().map(wa -> {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("id", wa.getId());
            m.put("question", wa.getQuestion());
            m.put("options", parseJsonArray(wa.getOptions()));
            m.put("userAnswer", wa.getUserAnswer());
            m.put("correctAnswer", wa.getCorrectAnswer());
            m.put("knowledgePoint", wa.getKnowledgePoint());
            m.put("quizTitle", wa.getQuizTitle());
            m.put("createdAt", wa.getCreatedAt() != null ? wa.getCreatedAt().toString() : null);
            return m;
        }).toList();
        return ResponseResult.success(result);
    }

    /** DELETE /api/quiz/wrong-answers/{studentId} */
    @DeleteMapping("/wrong-answers/{studentId}")
    public ResponseResult<String> clearWrongAnswers(@PathVariable String studentId) {
        wrongAnswerRepo.deleteByStudentId(studentId);
        return ResponseResult.success("cleared");
    }

    private List<String> parseJsonArray(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            @SuppressWarnings("unchecked")
            List<String> arr = objectMapper.readValue(json, List.class);
            return arr;
        } catch (Exception e) { return List.of(); }
    }

    /** Request body for saving wrong answer. */
    public record WrongAnswerRequest(
        String studentId, String question, List<String> options,
        String userAnswer, String correctAnswer,
        String knowledgePoint, String quizTitle
    ) {}

    /**
     * 答题提交请求体。
     */
    public record QuizSubmitRequest(
        String studentId,
        String knowledgePoint,
        List<AnswerResult> results
    ) {}
}
