package org.example.educatorweb.learninglog.api;

import org.example.educatorweb.dto.ResponseResult;
import org.example.educatorweb.learninglog.service.LearningBehaviorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/log")
public class LearningLogController {

    private static final Logger log = LoggerFactory.getLogger(LearningLogController.class);
    private final LearningBehaviorService service;

    public LearningLogController(LearningBehaviorService service) {
        this.service = service;
    }

    /** POST /api/log/browse — record a knowledge-point browse event */
    @PostMapping("/browse")
    public ResponseResult<String> logBrowse(@RequestBody Map<String, Object> body) {
        String studentId = (String) body.get("studentId");
        String concept = (String) body.get("concept");
        if (studentId == null || concept == null) {
            return ResponseResult.error("studentId and concept are required");
        }
        service.logKnowledgeBrowse(studentId, concept);
        return ResponseResult.success("ok");
    }

    /** POST /api/log/duration — record resource view duration (HTML) */
    @PostMapping("/duration")
    public ResponseResult<String> logDuration(@RequestBody Map<String, Object> body) {
        String studentId = (String) body.get("studentId");
        String concept = (String) body.get("concept");
        Object durObj = body.get("durationSeconds");
        int durationSeconds = durObj instanceof Number n ? n.intValue() : 0;

        if (studentId == null || concept == null) {
            return ResponseResult.error("studentId and concept are required");
        }
        service.logResourceView(studentId, concept, "HTML",
            body.get("resourceId") instanceof Number n ? n.longValue() : 0L,
            concept + " 交互课件 · " + durationSeconds + "秒");
        log.info("LearningLog: duration user={} concept={} seconds={}", studentId, concept, durationSeconds);
        return ResponseResult.success("ok");
    }

    /** POST /api/quiz/submit — submit quiz answers and get corrected results */
    @PostMapping("/quiz/submit")
    public ResponseResult<Map<String, Object>> submitQuiz(@RequestBody Map<String, Object> body) {
        String studentId = (String) body.get("studentId");
        String concept = (String) body.get("concept");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> answers = (List<Map<String, Object>>) body.get("answers");

        if (studentId == null || concept == null || answers == null) {
            return ResponseResult.error("studentId, concept, and answers are required");
        }

        int correct = 0;
        for (int i = 0; i < answers.size(); i++) {
            Map<String, Object> ans = answers.get(i);
            Object selected = ans.get("selected");
            Object expected = ans.get("expected");
            if (expected != null && expected.equals(selected)) {
                correct++;
            }
        }

        int total = answers.size();
        service.logQuizResults(studentId, concept, total, correct);

        log.info("LearningLog: quiz submitted user={} concept={} correct={}/{}", studentId, concept, correct, total);

        return ResponseResult.success(Map.of(
            "total", total,
            "correct", correct,
            "wrong", total - correct,
            "score", total > 0 ? Math.round((double) correct / total * 100) : 0
        ));
    }
}
