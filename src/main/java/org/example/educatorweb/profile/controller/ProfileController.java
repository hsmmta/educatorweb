package org.example.educatorweb.profile.controller;

import org.example.educatorweb.profile.ProfileAnalysisService;
import org.example.educatorweb.profile.ProfileService;
import org.example.educatorweb.profile.model.ProfileAnalysisResult;
import org.example.educatorweb.profile.model.StudentProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/profile")
public class ProfileController {

    private static final Logger log = LoggerFactory.getLogger(ProfileController.class);

    private final ProfileService profileService;
    private final ProfileAnalysisService analysisService;

    public ProfileController(ProfileService profileService,
                             ProfileAnalysisService analysisService) {
        this.profileService = profileService;
        this.analysisService = analysisService;
    }

    /**
     * 获取学生当前画像。
     */
    @GetMapping(value = "/{studentId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<?>> getProfile(@PathVariable String studentId) {
        return Mono.fromCallable(() -> {
            log.info("ProfileController: getProfile student={}", studentId);
            StudentProfile profile = profileService.getProfile(studentId);
            if (profile == null) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(profile);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 获取学生画像概览摘要（含统计数据）。
     * GET /api/profile/{studentId}/summary
     */
    @GetMapping(value = "/{studentId}/summary", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<?>> getProfileSummary(@PathVariable String studentId) {
        return Mono.<ResponseEntity<?>>fromCallable(() -> {
            log.info("ProfileController: getProfileSummary student={}", studentId);
            StudentProfile profile = profileService.getProfile(studentId);

            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("exists", profile != null);

            if (profile != null) {
                long learningDays = profile.getCreatedAt() != null
                    ? Math.max(1, ChronoUnit.DAYS.between(profile.getCreatedAt(), LocalDateTime.now()))
                    : 1;
                summary.put("learningDays", (int) learningDays);
                summary.put("resourceCount", 0);
                summary.put("quizCount", 0);

                double avg = averageConfidence(profile);
                summary.put("compositeScore", Math.round(avg * 100));

                summary.put("details", List.of(
                    Map.of("label", "知识掌握", "value", pct(profile.getKnowledgeBaseConfidence()), "color", "#667eea"),
                    Map.of("label", "练习正确率", "value", 0, "color", "#67c23a"),
                    Map.of("label", "学习投入度", "value", pct(profile.getLearningPaceConfidence()), "color", "#e6a23c"),
                    Map.of("label", "资源利用率", "value", 0, "color", "#f56c6c")
                ));

                summary.put("confidences", Map.of(
                    "knowledge", profile.getKnowledgeBaseConfidence().doubleValue(),
                    "cognitive", profile.getCognitiveStyleConfidence().doubleValue(),
                    "error", profile.getErrorPatternConfidence().doubleValue(),
                    "pace", profile.getLearningPaceConfidence().doubleValue(),
                    "goal", profile.getGoalOrientationConfidence().doubleValue()
                ));

                summary.put("knowledgeBaseLevel", profile.getKnowledgeBaseLevel());
                summary.put("cognitiveStyleType", profile.getCognitiveStyleType());
                summary.put("errorPatternTags", profile.getErrorPatternTags());
                summary.put("learningPaceType", profile.getLearningPaceType());
                summary.put("contentPreferenceType", profile.getContentPreferenceType());
                summary.put("goalOrientationType", profile.getGoalOrientationType());
            }

            return ResponseEntity.ok(summary);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private static int pct(BigDecimal bd) {
        if (bd == null) return 0;
        return (int) (bd.doubleValue() * 100);
    }

    private static double averageConfidence(StudentProfile p) {
        double sum = 0;
        int count = 0;
        BigDecimal[] vals = {
            p.getKnowledgeBaseConfidence(), p.getCognitiveStyleConfidence(),
            p.getErrorPatternConfidence(), p.getLearningPaceConfidence(),
            p.getGoalOrientationConfidence()
        };
        for (BigDecimal v : vals) {
            if (v != null) { sum += v.doubleValue(); count++; }
        }
        return count > 0 ? sum / count : 0;
    }

    /**
     * AI 画像分析接口。
     *
     * <p>触发 LangChain4j 智能体分析学生在 Chroma 中的自然对话记录，
     * 自动推断各画像维度的值，并更新到 MySQL。
     *
     * <pre>
     * POST /api/profile/analyze
     * { "studentId": "student-1" }
     * </pre>
     */
    @PostMapping(value = "/analyze", consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<?>> analyzeProfile(@RequestBody Map<String, String> body) {
        return Mono.fromCallable(() -> {
            String studentId = body.get("studentId");
            if (studentId == null || studentId.isBlank()) {
                return ResponseEntity.badRequest()
                    .body(Map.of("error", "studentId is required"));
            }

            log.info("ProfileController: analyze student={}", studentId);
            ProfileAnalysisResult result = analysisService.analyzeAndUpdate(studentId);
            return ResponseEntity.ok(result);
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
