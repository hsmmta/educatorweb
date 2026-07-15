package org.example.educatorweb.topicpush.api;

import org.example.educatorweb.dto.ResponseResult;
import org.example.educatorweb.knowledgegraph.model.KnowledgePoint;
import org.example.educatorweb.knowledgegraph.repository.KnowledgePointRepository;
import org.example.educatorweb.profile.model.StudentKnowledgeProficiency;
import org.example.educatorweb.profile.repository.StudentKnowledgeProficiencyRepository;
import org.example.educatorweb.topicpush.repository.TopicCacheRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 聚合接口：返回资源推送页面初始化和搜索补全所需的数据。
 * GET /api/push/context?studentId=xxx&q=搜索词(可选)
 */
@RestController
@RequestMapping("/api/push")
public class PushContextController {

    private final TopicCacheRepository topicCacheRepo;
    private final StudentKnowledgeProficiencyRepository kpProficiencyRepo;
    private final KnowledgePointRepository kpRepo;

    public PushContextController(TopicCacheRepository topicCacheRepo,
                                 StudentKnowledgeProficiencyRepository kpProficiencyRepo,
                                 KnowledgePointRepository kpRepo) {
        this.topicCacheRepo = topicCacheRepo;
        this.kpProficiencyRepo = kpProficiencyRepo;
        this.kpRepo = kpRepo;
    }

    @GetMapping("/context")
    public ResponseResult<Map<String, Object>> getContext(
            @RequestParam String studentId,
            @RequestParam(required = false) String q) {

        // 1. 最近学过：TopicCache 中该用户最近 5 个不重复话题标签
        List<String> recentTopics = topicCacheRepo
            .findRecentByUserId(studentId, PageRequest.of(0, 50))
            .stream()
            .map(org.example.educatorweb.topicpush.model.TopicCache::getTopicLabel)
            .distinct()
            .limit(5)
            .toList();

        // 2. 薄弱知识点：熟练度 < 0.6 的前 5 个
        List<Map<String, Object>> weaknessTopics = kpProficiencyRepo
            .findByStudentId(studentId)
            .stream()
            .filter(d -> d.getProficiency() != null
                && d.getProficiency().compareTo(new BigDecimal("0.6")) < 0)
            .sorted(Comparator.comparing(d ->
                d.getProficiency() != null ? d.getProficiency() : BigDecimal.ONE))
            .map(d -> Map.of(
                "concept", (Object) (d.getConcept() != null ? d.getConcept() : ""),
                "proficiency", (Object) (d.getProficiency() != null
                    ? d.getProficiency().doubleValue() : 0.0)
            ))
            .toList();

        // 3. 搜索补全候选（Neo4j 节点名 + TopicCache 话题名，合并去重）
        List<String> suggestions = List.of();
        if (q != null && !q.isBlank()) {
            // Neo4j 知识图谱节点名匹配
            List<String> kpNames = kpRepo.findAll().stream()
                .map(KnowledgePoint::getName)
                .filter(n -> n != null && n.toLowerCase().contains(q.toLowerCase()))
                .distinct()
                .collect(Collectors.toList());

            // TopicCache 话题名匹配（用户自己的排前面）
            List<String> topicNames = topicCacheRepo
                .findTopicLabelsByUserIdAndQuery(studentId, q);

            // 合并去重：用户话题优先
            Set<String> seen = new LinkedHashSet<>(topicNames);
            seen.addAll(kpNames);
            suggestions = new ArrayList<>(seen);
        }

        return ResponseResult.success(Map.of(
            "recentTopics", (Object) recentTopics,
            "weaknessTopics", (Object) weaknessTopics,
            "suggestions", (Object) suggestions
        ));
    }

    /**
     * GET /api/push/knowledge-points
     * Returns all knowledge points grouped by category, for the browse-by-category UI.
     */
    @GetMapping("/knowledge-points")
    public ResponseResult<Map<String, Object>> getKnowledgePoints() {
        List<KnowledgePointRepository.KnowledgePointSummary> all = kpRepo.findAllSummaries();

        // Group by category
        Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        for (var kp : all) {
            String cat = kp.category() != null ? kp.category() : "概念";
            grouped.computeIfAbsent(cat, k -> new ArrayList<>())
                .add(Map.of(
                    "id", (Object) kp.id(),
                    "name", (Object) kp.name(),
                    "difficulty", (Object) (kp.difficulty() != null ? kp.difficulty().intValue() : 3)
                ));
        }

        // Build ordered category list
        List<Map<String, Object>> categories = new ArrayList<>();
        // Preferred order
        List<String> order = List.of("数学基础", "概念", "算法", "应用", "工具");
        Set<String> added = new LinkedHashSet<>();
        for (String cat : order) {
            if (grouped.containsKey(cat)) {
                categories.add(Map.of("name", cat, "points", (Object) grouped.get(cat)));
                added.add(cat);
            }
        }
        // Any remaining categories
        for (var entry : grouped.entrySet()) {
            if (!added.contains(entry.getKey())) {
                categories.add(Map.of("name", entry.getKey(), "points", (Object) entry.getValue()));
            }
        }

        return ResponseResult.success(Map.of(
            "categories", (Object) categories,
            "totalCount", all.size()
        ));
    }
}
