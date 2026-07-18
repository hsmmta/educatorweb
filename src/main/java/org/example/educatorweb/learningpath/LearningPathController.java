package org.example.educatorweb.learningpath;

import org.example.educatorweb.dto.ResponseResult;
import org.example.educatorweb.learningpath.model.LearningPath;
import org.example.educatorweb.learningpath.model.PathNode;
import org.example.educatorweb.learningpath.model.RecommendedResource;
import org.example.educatorweb.profile.ProfileService;
import org.example.educatorweb.profile.ProficiencyService;
import org.example.educatorweb.profile.ProficiencyService.ProficiencyResult;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 学习路径规划与资源推送控制器。
 * 提供路径规划、资源推荐、进度更新等接口。
 */
@RestController
@RequestMapping("/api/push")
public class LearningPathController {

    private final LearningPathService pathService;
    private final ResourceRecommendService recommendService;
    private final ProfileService profileService;
    private final ProficiencyService proficiencyService;

    public LearningPathController(LearningPathService pathService,
                                  ResourceRecommendService recommendService,
                                  ProfileService profileService,
                                  ProficiencyService proficiencyService) {
        this.pathService = pathService;
        this.recommendService = recommendService;
        this.profileService = profileService;
        this.proficiencyService = proficiencyService;
    }

    /**
     * Get the saved learning path for a student.
     * GET /api/push/path/{studentId}/saved
     */
    @GetMapping("/path/{studentId}/saved")
    public ResponseResult<Map<String, Object>> getSavedPath(
            @PathVariable String studentId) {
        String json = profileService.getSavedLearningPathJson(studentId);
        if (json == null || json.isEmpty()) {
            return ResponseResult.success(Map.of("exists", false));
        }
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            mapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
            LearningPath path = mapper.readValue(json, LearningPath.class);

            // Enrich each node with real-time proficiency AND recompute statuses
            if (path.getNodes() != null) {
                java.util.List<ProficiencyResult> allProfs = proficiencyService.getAllProficiencies(studentId);
                boolean foundCurrent = false;
                for (var node : path.getNodes()) {
                    String nodeName = node.getKnowledgePointName();
                    if (nodeName == null) nodeName = "";
                    double bestProficiency = 0.0;
                    for (ProficiencyResult pr : allProfs) {
                        String c = pr.concept();
                        if (c == null) continue;
                        if (c.equals(nodeName)
                            || c.contains(nodeName)
                            || nodeName.contains(c)) {
                            if (pr.effectiveProficiency() > bestProficiency) {
                                bestProficiency = pr.effectiveProficiency();
                            }
                        }
                    }
                    node.setProficiency(bestProficiency);

                    // Recompute status: ≥60% proficiency → COMPLETED (confidence is informative only)
                    if (bestProficiency >= 0.6) {
                        node.setStatus(PathNode.PathNodeStatus.COMPLETED);
                    } else if (!foundCurrent) {
                        node.setStatus(PathNode.PathNodeStatus.CURRENT);
                        foundCurrent = true;
                    } else {
                        node.setStatus(PathNode.PathNodeStatus.PENDING);
                    }
                }
                if (!foundCurrent && !path.getNodes().isEmpty()) {
                    path.getNodes().get(0).setStatus(PathNode.PathNodeStatus.CURRENT);
                }

                // Update completed count
                path.setCompletedNodes((int) path.getNodes().stream()
                    .filter(n -> n.getStatus() == PathNode.PathNodeStatus.COMPLETED).count());
            }

            return ResponseResult.success(Map.of("exists", true, "path", path));
        } catch (Exception e) {
            return ResponseResult.success(Map.of("exists", false, "error", "parse failed: " + e.getMessage()));
        }
    }

    /**
     * 为指定学生和目标知识点规划学习路径。
     * GET /api/push/path/{studentId}?target=支持向量机
     */
    @GetMapping("/path/{studentId}")
    public ResponseResult<LearningPath> getLearningPath(
            @PathVariable String studentId,
            @RequestParam(defaultValue = "机器学习") String target) {
        LearningPath path = pathService.planPath(studentId, target);
        return ResponseResult.success(path);
    }

    /**
     * 获取今日推荐资源。
     * GET /api/push/recommend/{studentId}?target=支持向量机
     */
    @GetMapping("/recommend/{studentId}")
    public ResponseResult<Map<String, Object>> getRecommendations(
            @PathVariable String studentId,
            @RequestParam(required = false) String target) {
        ResourceRecommendService.RecommendationResult result =
            recommendService.getDailyRecommendations(studentId, target);

        return ResponseResult.success(Map.of(
            "allRecommendations", (Object) result.allRecommendations(),
            "profileBased", (Object) result.profileBased(),
            "progressBased", (Object) result.progressBased(),
            "weaknessBased", (Object) result.weaknessBased(),
            "learningPath", (Object) result.learningPath(),
            "pushStrategies", result.learningPath() != null
                ? result.learningPath().getPushStrategies() : List.of()
        ));
    }

    /**
     * 更新节点学习进度。
     * POST /api/push/progress/{studentId}
     * Body: { "knowledgePointId": "svm", "completed": true }
     */
    @PostMapping("/progress/{studentId}")
    public ResponseResult<String> updateProgress(
            @PathVariable String studentId,
            @RequestBody ProgressUpdateRequest request) {
        // TODO: 持久化进度更新
        // 当前版本：返回确认信息，实际持久化在 P0-5（学习效果评估）中完整实现
        return ResponseResult.success("Progress updated: " + request.knowledgePointId()
            + " -> " + (request.completed() ? "completed" : "in_progress"));
    }

    /**
     * 进度更新请求。
     */
    public record ProgressUpdateRequest(String knowledgePointId, boolean completed) {}
}