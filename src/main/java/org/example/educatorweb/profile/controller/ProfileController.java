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
