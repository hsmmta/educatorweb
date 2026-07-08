package org.example.educatorweb.learningpath;

import org.example.educatorweb.learningpath.model.LearningPath;
import org.example.educatorweb.learningpath.model.PathNode;
import org.example.educatorweb.learningpath.model.PathNode.PathNodeStatus;
import org.example.educatorweb.learningpath.model.RecommendedResource;
import org.example.educatorweb.profile.ProfileService;
import org.example.educatorweb.profile.model.StudentProfile;
import org.example.educatorweb.profile.model.StudentKnowledgeProficiency;
import org.example.educatorweb.profile.repository.StudentKnowledgeProficiencyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 资源推荐引擎。
 * 基于学生画像、知识掌握情况和学习进度，生成个性化资源推荐。
 */
@Service
public class ResourceRecommendService {

    private static final Logger log = LoggerFactory.getLogger(ResourceRecommendService.class);

    private final ProfileService profileService;
    private final LearningPathService pathService;
    private final StudentKnowledgeProficiencyRepository kpProficiencyRepo;

    public ResourceRecommendService(ProfileService profileService, LearningPathService pathService,
                                     StudentKnowledgeProficiencyRepository kpProficiencyRepo) {
        this.profileService = profileService;
        this.pathService = pathService;
        this.kpProficiencyRepo = kpProficiencyRepo;
    }

    /**
     * 获取今日推荐资源列表。
     * 综合画像、进度和薄弱点三个维度生成推荐。
     */
    public RecommendationResult getDailyRecommendations(String studentId, String targetKnowledgePoint) {
        StudentProfile profile = profileService.getProfile(studentId);

        // 1. 获取学习路径（如果有的话）
        LearningPath path = null;
        if (targetKnowledgePoint != null && !targetKnowledgePoint.isBlank()) {
            path = pathService.planPath(studentId, targetKnowledgePoint);
        }

        // 2. 基于画像推荐（内容偏好维度）
        List<RecommendedResource> profileBased = buildProfileBasedRecommendations(profile);

        // 3. 基于薄弱点推荐
        List<RecommendedResource> weaknessBased = buildWeaknessBasedRecommendations(studentId, profile);

        // 4. 基于进度推荐（当前学习节点）
        List<RecommendedResource> progressBased = buildProgressBasedRecommendations(path);

        // 5. 合并去重，按优先级排序
        List<RecommendedResource> allResources = mergeAndDedup(
            profileBased, progressBased, weaknessBased);

        return new RecommendationResult(allResources, profileBased, progressBased, weaknessBased, path);
    }

    /**
     * 基于画像的内容偏好推荐。
     */
    private List<RecommendedResource> buildProfileBasedRecommendations(StudentProfile profile) {
        if (profile == null) return List.of();

        List<RecommendedResource> resources = new ArrayList<>();
        String pref = profile.getContentPreferenceType();
        String cognitive = profile.getCognitiveStyleType();

        if ("视频优先".equals(pref)) {
            resources.add(new RecommendedResource("核心知识点视频讲解", "VIDEO",
                "匹配你的" + pref + "偏好", 10));
        }
        if ("文档优先".equals(pref) || "分析型".equals(cognitive)) {
            resources.add(new RecommendedResource("深度技术文档阅读", "DOC",
                "基于你的" + (cognitive != null ? cognitive : pref) + "风格推荐", 9));
        }
        if ("视觉型".equals(cognitive)) {
            resources.add(new RecommendedResource("知识结构思维导图", "MINDMAP",
                "匹配你的视觉型学习风格", 9));
        }
        resources.add(new RecommendedResource("综合练习题集", "QUIZ",
            "巩固知识点的针对性练习", 8));
        resources.add(new RecommendedResource("实战代码案例", "CODE",
            "理论结合实践的代码示例", 7));

        return resources;
    }

    /**
     * 基于知识薄弱点的推荐。
     */
    private List<RecommendedResource> buildWeaknessBasedRecommendations(
            String studentId, StudentProfile profile) {
        // 直接查 StudentKnowledgeProficiency 表，不碰 StudentProfile 的懒加载集合。
        // WebFlux 下 Lazy 集合在 Session 关闭后是不可用的代理 —— 独立查询根治。
        List<StudentKnowledgeProficiency> details = kpProficiencyRepo.findByStudentId(studentId);
        if (details == null || details.isEmpty()) return List.of();

        List<RecommendedResource> resources = new ArrayList<>();
        // 找出熟练度最低的3个知识点
        details.stream()
            .filter(d -> d.getProficiency() != null && d.getProficiency().doubleValue() < 0.6)
            .sorted(Comparator.comparing(d -> d.getProficiency() != null ? d.getProficiency().doubleValue() : 1.0))
            .limit(3)
            .forEach(weak -> {
                resources.add(new RecommendedResource(
                    weak.getConcept() + " 强化练习", "QUIZ",
                    "针对你的知识薄弱点：" + weak.getConcept(), 10));
                resources.add(new RecommendedResource(
                    weak.getConcept() + " 专题讲解", "DOC",
                    "补充薄弱知识点的系统学习材料", 9));
            });

        // 基于易错标签推荐
        if (profile.getErrorPatternTags() != null && !profile.getErrorPatternTags().isEmpty()) {
            for (String tag : profile.getErrorPatternTags()) {
                resources.add(new RecommendedResource(
                    tag + " 专题突破", "QUIZ",
                    "针对你的易错类型：" + tag, 8));
            }
        }

        return resources;
    }

    /**
     * 基于学习进度的推荐（当前学习节点）。
     */
    private List<RecommendedResource> buildProgressBasedRecommendations(LearningPath path) {
        if (path == null || path.getNodes() == null) return List.of();

        List<RecommendedResource> resources = new ArrayList<>();
        for (PathNode node : path.getNodes()) {
            if (node.getStatus() == PathNodeStatus.CURRENT
                && node.getRecommendedResources() != null) {
                resources.addAll(node.getRecommendedResources());
            }
        }
        return resources;
    }

    /**
     * 合并去重、排序。
     */
    private List<RecommendedResource> mergeAndDedup(
            List<RecommendedResource>... lists) {
        Map<String, RecommendedResource> seen = new LinkedHashMap<>();
        for (List<RecommendedResource> list : lists) {
            for (RecommendedResource r : list) {
                String key = r.getTitle() + "|" + r.getResourceType();
                if (!seen.containsKey(key) || seen.get(key).getPriority() < r.getPriority()) {
                    seen.put(key, r);
                }
            }
        }
        return seen.values().stream()
            .sorted((a, b) -> Integer.compare(b.getPriority(), a.getPriority()))
            .limit(8)
            .toList();
    }

    /**
     * 推荐结果。
     */
    public record RecommendationResult(
        List<RecommendedResource> allRecommendations,
        List<RecommendedResource> profileBased,
        List<RecommendedResource> progressBased,
        List<RecommendedResource> weaknessBased,
        LearningPath learningPath
    ) {
        public boolean isEmpty() {
            return allRecommendations.isEmpty() && learningPath == null;
        }
    }

    /**
     * Generate resource recommendations for a single topic label.
     * Used by topic-push to generate resources per cached topic.
     */
    public List<RecommendedResource> recommendByTopic(String studentId,
                                                        String topicLabel,
                                                        String contextText) {
        StudentProfile profile = profileService.getProfile(studentId);

        List<RecommendedResource> resources = new ArrayList<>();

        // Generate resources for this topic
        resources.add(new RecommendedResource(
            topicLabel + " 系统讲解", "DOC",
            "基于你的学习画像推荐", 9));
        resources.add(new RecommendedResource(
            topicLabel + " 巩固练习", "QUIZ",
            "巩固知识点的针对性练习", 8));
        resources.add(new RecommendedResource(
            topicLabel + " 思维导图", "MINDMAP",
            "梳理" + topicLabel + "的知识框架", 7));

        // Add profile-based recommendations if profile exists
        if (profile != null) {
            String pref = profile.getContentPreferenceType();
            if ("video".equals(pref)) {
                resources.add(new RecommendedResource(
                    topicLabel + " 视频讲解", "VIDEO",
                    "匹配你的视频优先偏好", 10));
            }
            if ("interactive".equals(pref)) {
                resources.add(new RecommendedResource(
                    topicLabel + " 实战代码", "CODE",
                    "匹配你的交互式学习偏好", 8));
            }
        }

        return resources;
    }
}