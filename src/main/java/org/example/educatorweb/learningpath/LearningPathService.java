package org.example.educatorweb.learningpath;

import org.example.educatorweb.knowledgegraph.model.KnowledgePoint;
import org.example.educatorweb.knowledgegraph.model.LearningResource;
import org.example.educatorweb.knowledgegraph.repository.KnowledgePointRepository;
import org.example.educatorweb.learningpath.model.LearningPath;
import org.example.educatorweb.learningpath.model.LearningPath.PushStrategy;
import org.example.educatorweb.learningpath.model.PathNode;
import org.example.educatorweb.learningpath.model.PathNode.PathNodeStatus;
import org.example.educatorweb.learningpath.model.RecommendedResource;
import org.example.educatorweb.profile.ProficiencyService;
import org.example.educatorweb.profile.ProfileService;
import org.example.educatorweb.profile.model.StudentKnowledgeProficiency;
import org.example.educatorweb.profile.model.StudentProfile;
import org.example.educatorweb.profile.repository.StudentKnowledgeProficiencyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 个性化学习路径规划服务。
 * 基于知识图谱的拓扑结构 + 学生画像，生成有序的学习路径。
 */
@Service
public class LearningPathService {

    private static final Logger log = LoggerFactory.getLogger(LearningPathService.class);

    private final KnowledgePointRepository kpRepo;
    private final ProfileService profileService;
    private final StudentKnowledgeProficiencyRepository kpProficiencyRepo;
    private final ApplicationEventPublisher eventPublisher;

    /** 每个知识点推荐的资源类型和对应图标 */
    private static final List<ResourceSlot> DEFAULT_RESOURCE_SLOTS = List.of(
        new ResourceSlot("DOC", "课程文档", "📄", 10),
        new ResourceSlot("QUIZ", "练习题库", "📝", 8),
        new ResourceSlot("CODE", "代码案例", "💻", 7),
        new ResourceSlot("HTML", "交互课件", "🌐", 5)
    );

    public LearningPathService(KnowledgePointRepository kpRepo, ProfileService profileService,
                               StudentKnowledgeProficiencyRepository kpProficiencyRepo,
                               ApplicationEventPublisher eventPublisher) {
        this.kpRepo = kpRepo;
        this.profileService = profileService;
        this.kpProficiencyRepo = kpProficiencyRepo;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 为指定学生和目标知识点规划学习路径。
     *
     * @param studentId            学生ID
     * @param targetKnowledgePoint 目标知识点名称（中文名如 "支持向量机"）
     * @return 完整的学习路径
     */
    public LearningPath planPath(String studentId, String targetKnowledgePoint) {
        // Remember target for auto re-planning on profile update
        if (targetKnowledgePoint != null && !targetKnowledgePoint.isBlank()) {
            lastTarget.put(studentId, targetKnowledgePoint);
        }

        // 1. 查找目标知识点（Neo4j may be unavailable, wrap with fallback）
        Optional<KnowledgePoint> targetOpt = Optional.empty();
        try {
            targetOpt = kpRepo.findByName(targetKnowledgePoint);
            if (targetOpt.isEmpty()) {
                targetOpt = kpRepo.findById(targetKnowledgePoint);
            }
        } catch (Exception e) {
            log.warn("LearningPathService: Neo4j unavailable — {}", e.getMessage());
        }

        // 2. 获取学生画像
        StudentProfile profile = profileService.getProfile(studentId);

        // 3. BFS 收集所有前置依赖链
        List<KnowledgePoint> orderedNodes = new ArrayList<>();
        Set<String> visited = new HashSet<>();

        if (targetOpt.isPresent()) {
            KnowledgePoint target = targetOpt.get();
            try {
                bfsCollectPrerequisites(target.getId(), visited, orderedNodes);
            } catch (Exception e) {
                log.warn("LearningPathService: BFS failed — {}", e.getMessage());
            }
            // 添加目标节点本身
            if (!visited.contains(target.getId())) {
                orderedNodes.add(target);
            }
        } else {
            log.warn("Knowledge point '{}' not found in graph, returning empty path", targetKnowledgePoint);
        }

        // 4. 标记节点状态（结合 proficiency 数据）
        Map<String, StudentKnowledgeProficiency> proficiencyMap = buildProficiencyMap(studentId);
        for (PathNode node : buildPathNodes(orderedNodes, 0)) {
            // 所有节点初始状态由 buildPathNodes 设置
        }

        // 5. 构建路径节点（带状态标记 + 薄弱点强化单元）
        List<PathNode> pathNodes = buildPathNodes(orderedNodes, 0);
        markNodeStatuses(pathNodes, proficiencyMap);
        insertReinforcementUnits(pathNodes, proficiencyMap);

        // 6. 根据画像偏好为每个节点推荐资源
        for (PathNode node : pathNodes) {
            node.setRecommendedResources(recommendResourcesForNode(node, profile));
        }

        // 7. 估算学习时长
        int totalDays = estimateTotalDays(pathNodes, profile);

        // 8. 组装路径
        LearningPath path = new LearningPath();
        path.setPathId(UUID.randomUUID().toString());
        path.setStudentId(studentId);
        path.setTargetKnowledgePoint(targetKnowledgePoint);
        path.setTitle(targetOpt.map(KnowledgePoint::getName).orElse(targetKnowledgePoint) + " 学习路径");
        path.setDescription(targetOpt.map(KnowledgePoint::getDescription).orElse("个性化学习路径规划"));
        path.setNodes(pathNodes);
        path.setTotalNodes(pathNodes.size());
        path.setCompletedNodes((int) pathNodes.stream().filter(n -> n.getStatus() == PathNodeStatus.COMPLETED).count());
        path.setEstimatedTotalDays(totalDays);
        path.setCreatedAt(LocalDateTime.now());
        path.setUpdatedAt(LocalDateTime.now());
        path.setPushStrategies(buildPushStrategies(profile));

        // Persist path to student profile for later retrieval
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            mapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
            String pathJson = mapper.writeValueAsString(path);
            profileService.saveLearningPath(studentId, pathJson);
        } catch (Exception e) {
            log.warn("LearningPathService: failed to persist path: {}", e.getMessage());
        }

        return path;
    }

    /**
     * BFS 收集所有前置依赖（包括间接依赖）。
     * 拓扑排序：先修课程排在前面。
     */
    private void bfsCollectPrerequisites(String kpId, Set<String> visited, List<KnowledgePoint> result) {
        if (visited.contains(kpId)) return;

        // 先处理所有前置依赖
        List<KnowledgePoint> prereqs = kpRepo.findPrerequisites(kpId);
        for (KnowledgePoint prereq : prereqs) {
            bfsCollectPrerequisites(prereq.getId(), visited, result);
        }

        // 再添加自身
        if (!visited.contains(kpId)) {
            visited.add(kpId);
            kpRepo.findById(kpId).ifPresent(result::add);
        }
    }

    /**
     * 构建路径节点列表。
     */
    private List<PathNode> buildPathNodes(List<KnowledgePoint> kps, int startOrder) {
        List<PathNode> nodes = new ArrayList<>();
        for (int i = 0; i < kps.size(); i++) {
            KnowledgePoint kp = kps.get(i);
            PathNode node = new PathNode(
                kp.getId(), kp.getName(), kp.getDescription(),
                kp.getDifficulty(), kp.getCategory(), startOrder + i
            );
            node.setEstimatedDuration(estimateNodeDuration(kp));
            nodes.add(node);
        }
        return nodes;
    }

    /**
     * 标记节点状态：已掌握/当前学习/待学习。
     * 使用有效掌握度（含艾宾浩斯遗忘衰减），同时满足 effectiveProficiency >= 0.8 且
     * confidence >= 0.5 才标记为 COMPLETED。超过 30 天未复习的节点即使曾经满分也会因衰减降级。
     */
    private void markNodeStatuses(List<PathNode> nodes, Map<String, StudentKnowledgeProficiency> profMap) {
        boolean foundCurrent = false;
        for (PathNode node : nodes) {
            StudentKnowledgeProficiency prof = profMap.get(node.getKnowledgePointId());
            double rawProficiency = prof != null && prof.getProficiency() != null
                ? prof.getProficiency().doubleValue() : 0.0;
            double effectiveProficiency = ProficiencyService.effectiveProficiency(
                rawProficiency, prof != null ? prof.getLastStudyTime() : null);
            double confidence = ProficiencyService.confidence(
                prof != null ? prof.getTotalQuestions() : 0);

            if (effectiveProficiency >= 0.8 && confidence >= 0.5) {
                node.setStatus(PathNodeStatus.COMPLETED);
            } else if (!foundCurrent) {
                node.setStatus(PathNodeStatus.CURRENT);
                foundCurrent = true;
            } else {
                node.setStatus(PathNodeStatus.PENDING);
            }
        }
        if (!foundCurrent && !nodes.isEmpty()) {
            nodes.get(0).setStatus(PathNodeStatus.CURRENT);
        }
    }

    /**
     * 根据学生画像为知识点推荐资源。
     */
    private List<RecommendedResource> recommendResourcesForNode(PathNode node, StudentProfile profile) {
        List<RecommendedResource> resources = new ArrayList<>();

        // 根据画像的内容偏好调整资源类型优先级
        List<ResourceSlot> slots = adjustSlotsByProfile(profile);

        // 尝试从知识图谱中获取已有的学习资源
        List<LearningResource> kgResources = kpRepo.findResources(node.getKnowledgePointId());

        if (!kgResources.isEmpty()) {
            for (int i = 0; i < Math.min(kgResources.size(), 4); i++) {
                LearningResource lr = kgResources.get(i);
                RecommendedResource rec = new RecommendedResource(
                    lr.getTitle(), mapKgTypeToResourceType(lr.getType()), "来自知识图谱推荐", 8 - i);
                rec.setResourceId(lr.getId());
                resources.add(rec);
            }
        }

        // 补充系统推荐的资源类型（确保覆盖多种类型）
        Set<String> existingTypes = new HashSet<>();
        for (RecommendedResource r : resources) {
            existingTypes.add(r.getResourceType());
        }

        for (ResourceSlot slot : slots) {
            if (!existingTypes.contains(slot.type()) && resources.size() < 5) {
                String reason = determineReason(slot, profile);
                RecommendedResource rec = new RecommendedResource(
                    node.getKnowledgePointName() + " " + slot.label(),
                    slot.type(), reason, slot.defaultPriority());
                resources.add(rec);
            }
        }

        return resources;
    }

    /**
     * 根据学生画像调整资源类型优先级。
     */
    private List<ResourceSlot> adjustSlotsByProfile(StudentProfile profile) {
        if (profile == null || profile.getContentPreferenceType() == null) {
            return DEFAULT_RESOURCE_SLOTS;
        }

        // 根据内容偏好类型调整顺序
        String pref = profile.getContentPreferenceType();
        List<ResourceSlot> adjusted = new ArrayList<>(DEFAULT_RESOURCE_SLOTS);

        return switch (pref) {
            case "视频优先" -> {
                adjusted.add(0, new ResourceSlot("VIDEO", "教学视频", "🎬", 10));
                yield adjusted;
            }
            case "文档优先" -> {
                // DOC already first
                yield adjusted;
            }
            case "混合学习" -> adjusted;
            default -> adjusted;
        };
    }

    private String determineReason(ResourceSlot slot, StudentProfile profile) {
        if (profile == null) return "为你推荐的学习资源";

        return switch (slot.type()) {
            case "DOC" -> "基于你的" + (profile.getCognitiveStyleType() != null ? profile.getCognitiveStyleType() : "学习") + "风格推荐";
            case "QUIZ" -> "针对你的薄弱环节巩固练习";
            case "CODE" -> "匹配你的实践学习偏好";
            case "HTML" -> "交互式课件可视化学习";
            case "VIDEO" -> "基于你的" + (profile.getContentPreferenceType() != null ? profile.getContentPreferenceType() : "学习") + "偏好推荐";
            default -> "为你推荐的学习资源";
        };
    }

    private String mapKgTypeToResourceType(String kgType) {
        if (kgType == null) return "DOC";
        return switch (kgType.toUpperCase()) {
            case "TEXTBOOK" -> "DOC";
            case "VIDEO" -> "VIDEO";
            case "EXERCISE" -> "QUIZ";
            case "CODE" -> "CODE";
            case "PAPER" -> "DOC";
            default -> "DOC";
        };
    }

    private Map<String, StudentKnowledgeProficiency> buildProficiencyMap(String studentId) {
        // 直接查 StudentKnowledgeProficiency 表，不碰 StudentProfile 的懒加载集合。
        // WebFlux 下 @Transactional 没有线程绑定 Session，离开 repository 方法后
        // Lazy 集合是失效代理，遍历即炸 —— 独立查询全量加载的实体即可根治。
        List<StudentKnowledgeProficiency> details = kpProficiencyRepo.findByStudentId(studentId);
        if (details == null || details.isEmpty()) return Map.of();
        Map<String, StudentKnowledgeProficiency> map = new LinkedHashMap<>();
        for (StudentKnowledgeProficiency detail : details) {
            map.put(detail.getConcept(), detail);
        }
        return map;
    }

    private String estimateNodeDuration(KnowledgePoint kp) {
        return switch (kp.getDifficulty()) {
            case 1 -> "1-2天";
            case 2 -> "2-3天";
            case 3 -> "3-5天";
            case 4 -> "5-7天";
            case 5 -> "1-2周";
            default -> "2-3天";
        };
    }

    private int estimateTotalDays(List<PathNode> nodes, StudentProfile profile) {
        int total = 0;
        for (PathNode node : nodes) {
            // 已掌握的节点不计入
            if (node.getStatus() == PathNodeStatus.COMPLETED) continue;
            total += switch (node.getDifficulty()) {
                case 1 -> 1;
                case 2 -> 2;
                case 3 -> 4;
                case 4 -> 6;
                case 5 -> 10;
                default -> 2;
            };
        }
        // 根据学习节奏调整
        if (profile != null && "快速推进型".equals(profile.getLearningPaceType())) {
            total = (int) (total * 0.7);
        } else if (profile != null && "稳扎稳打型".equals(profile.getLearningPaceType())) {
            total = (int) (total * 1.2);
        }
        return Math.max(total, 1);
    }

    private List<PushStrategy> buildPushStrategies(StudentProfile profile) {
        return List.of(
            new PushStrategy("🧠", "基于画像",
                "根据你的6维学习画像（" +
                    (profile != null && profile.getCognitiveStyleType() != null ? profile.getCognitiveStyleType() : "待构建") +
                    "、" + (profile != null && profile.getContentPreferenceType() != null ? profile.getContentPreferenceType() : "待构建") +
                    "），匹配最适合的学习内容和难度"),
            new PushStrategy("📈", "基于进度",
                "实时追踪学习进度，动态调整推送节奏和内容顺序"),
            new PushStrategy("🔄", "基于反馈",
                "根据练习测试和资源使用反馈，持续优化推送策略")
        );
    }

    // ─── Dynamic path adjustment ──────────────────────────────

    /** Cached last target per student for re-planning on profile update. */
    private final Map<String, String> lastTarget = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Listen for profile updates: if the student's knowledge-base level changed,
     * recalculate their last known learning path.
     */
    @Async
    @EventListener
    public void onProfileUpdated(ProfileUpdatedEvent event) {
        String last = lastTarget.get(event.studentId());
        if (last != null && !last.isBlank()) {
            log.info("LearningPathService: re-planning path for user={} due to profile change (D1→{})",
                event.studentId(), event.newKnowledgeBaseLevel());
            try {
                LearningPath path = planPath(event.studentId(), last);
                // Publish event so PathNotificationListener broadcasts via SSE
                eventPublisher.publishEvent(new PathRecalculatedEvent(
                    this, event.studentId(),
                    path.getTotalNodes() - path.getCompletedNodes()));
                log.info("LearningPathService: path recalculated — remaining nodes={}",
                    path.getTotalNodes() - path.getCompletedNodes());
            } catch (Exception e) {
                log.debug("LearningPathService: re-planning failed (non-critical): {}", e.getMessage());
            }
        }
    }

    // ─── Reinforcement units for weak nodes ────────────────────

    /**
     * Insert reinforcement sub-nodes before weak prerequisite nodes.
     * For each node with proficiency &lt; 0.6 (not the first CURRENT node),
     * insert a REINFORCEMENT node with half difficulty + extra quiz resources.
     */
    private void insertReinforcementUnits(List<PathNode> nodes,
                                           Map<String, StudentKnowledgeProficiency> profMap) {
        List<PathNode> enriched = new ArrayList<>();
        boolean firstCurrentPassed = false;

        for (PathNode node : nodes) {
            if (node.getStatus() == PathNodeStatus.CURRENT) {
                firstCurrentPassed = true;
                enriched.add(node);
                continue;
            }

            StudentKnowledgeProficiency prof = profMap.get(node.getKnowledgePointId());
            boolean isWeak = prof != null && prof.getProficiency() != null
                && prof.getProficiency().doubleValue() < 0.6;

            // Insert reinforcement unit before this weak node (only after CURRENT)
            if (isWeak && firstCurrentPassed) {
                PathNode reinforcement = new PathNode(
                    node.getKnowledgePointId() + "_reinforce",
                    "📌 " + node.getKnowledgePointName() + "（强化）",
                    "针对" + node.getKnowledgePointName() + "的专项强化练习",
                    Math.max(1, node.getDifficulty() / 2),
                    node.getCategory(),
                    node.getOrder()
                );
                reinforcement.setStatus(PathNodeStatus.PENDING);
                reinforcement.setEstimatedDuration("1天");
                // Extra quiz-focused resources for reinforcement
                reinforcement.setRecommendedResources(List.of(
                    new RecommendedResource(node.getKnowledgePointName() + " 基础巩固题", "QUIZ",
                        "薄弱点专项训练：从基础题起步", 10),
                    new RecommendedResource(node.getKnowledgePointName() + " 概念回顾", "DOC",
                        "重新梳理核心概念", 9)
                ));
                enriched.add(reinforcement);
            }

            enriched.add(node);
        }
        nodes.clear();
        nodes.addAll(enriched);
    }

    /**
     * 资源推荐槽位定义。
     */
    private record ResourceSlot(String type, String label, String icon, int defaultPriority) {}
}