package org.example.educatorweb.profile.controller;

import org.example.educatorweb.profile.LearningReportService;
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
    private final LearningReportService reportService;

    public ProfileController(ProfileService profileService,
                             ProfileAnalysisService analysisService,
                             LearningReportService reportService) {
        this.profileService = profileService;
        this.analysisService = analysisService;
        this.reportService = reportService;
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
    /**
     * AI 画像分析接口。
     *
     * 触发 LangChain4j 智能体分析学生在 Chroma 中的自然对话记录，
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

    /**
     * 画像概览与学习评估摘要。
     *
     * 返回前端个人中心所需的完整数据：六维画像取值与置信度、学习统计、
     * 综合评分、强弱项分析、学习建议。
     *
     * <pre>
     * GET /api/profile/{studentId}/summary
     * </pre>
     */
    @GetMapping(value = "/{studentId}/summary", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> getProfileSummary(@PathVariable String studentId) {
        return Mono.fromCallable(() -> {
            log.info("ProfileController: summary for student={}", studentId);
            Map<String, Object> summary = reportService.generateProfileSummary(studentId);
            return ResponseEntity.ok(summary);
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
