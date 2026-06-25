package org.example.educatorweb.profile;

import org.example.educatorweb.dto.ResponseResult;
import org.example.educatorweb.profile.model.StudentProfile;
import org.springframework.web.bind.annotation.*;

/**
 * 学生画像 REST 控制器。
 * 提供画像的查询和更新接口。
 */
@RestController
@RequestMapping("/api/profile")
public class ProfileController {

    private final ProfileService profileService;

    public ProfileController(ProfileService profileService) {
        this.profileService = profileService;
    }

    /**
     * 获取学生的6维学习画像。
     */
    @GetMapping("/{studentId}")
    public ResponseResult<StudentProfile> getProfile(@PathVariable String studentId) {
        StudentProfile profile = profileService.getProfile(studentId);
        if (profile == null) {
            return ResponseResult.error("Profile not found for student: " + studentId);
        }
        return ResponseResult.success(profile);
    }

    /**
     * 获取画像简要信息（用于前端快速展示）。
     * 如果画像不存在，返回默认空值而不报错。
     */
    @GetMapping("/{studentId}/summary")
    public ResponseResult<ProfileSummary> getProfileSummary(@PathVariable String studentId) {
        StudentProfile profile = profileService.getProfile(studentId);
        if (profile == null) {
            return ResponseResult.success(ProfileSummary.empty());
        }
        return ResponseResult.success(ProfileSummary.from(profile));
    }

    /**
     * 画像简要信息 DTO。
     */
    public record ProfileSummary(
        String knowledgeBaseLevel,
        String cognitiveStyleType,
        String learningPaceType,
        String contentPreferenceType,
        String goalOrientationType,
        java.util.List<String> errorPatternTags,
        java.util.Map<String, Double> contentPreferenceRatio,
        java.util.Map<String, java.math.BigDecimal> confidences,
        boolean exists
    ) {
        static ProfileSummary empty() {
            return new ProfileSummary(null, null, null, null, null,
                java.util.List.of(), java.util.Map.of(), java.util.Map.of(), false);
        }

        static ProfileSummary from(StudentProfile p) {
            java.util.Map<String, java.math.BigDecimal> confidences = new java.util.LinkedHashMap<>();
            confidences.put("knowledge", p.getKnowledgeBaseConfidence());
            confidences.put("cognitive", p.getCognitiveStyleConfidence());
            confidences.put("error", p.getErrorPatternConfidence());
            confidences.put("pace", p.getLearningPaceConfidence());
            confidences.put("goal", p.getGoalOrientationConfidence());
            return new ProfileSummary(
                p.getKnowledgeBaseLevel(),
                p.getCognitiveStyleType(),
                p.getLearningPaceType(),
                p.getContentPreferenceType(),
                p.getGoalOrientationType(),
                p.getErrorPatternTags() != null ? p.getErrorPatternTags() : java.util.List.of(),
                p.getContentPreferenceRatio() != null ? p.getContentPreferenceRatio() : java.util.Map.of(),
                confidences,
                true
            );
        }
    }
}