package org.example.educatorweb.profile;

import org.example.educatorweb.profile.model.StudentProfile;
import org.example.educatorweb.profile.model.StudentKnowledgeProficiency;
import org.example.educatorweb.profile.model.ProficiencySnapshot;
import org.example.educatorweb.profile.repository.ProficiencySnapshotRepository;
import org.example.educatorweb.learninglog.model.LearningBehaviorLog;
import org.example.educatorweb.learninglog.repository.LearningBehaviorLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.WeekFields;
import java.util.*;

/**
 * 学习报告服务。
 * 综合分析学生的知识点掌握度数据（含艾宾浩斯遗忘衰减），生成评估报告。
 *
 * <p>报告包含：
 * <ul>
 *   <li>总体统计（总答题数、平均有效掌握度、综合评分）</li>
 *   <li>强弱项分析（掌握度排序，标注薄弱/优秀知识点）</li>
 *   <li>学习建议（基于薄弱点的个性化复习/练习推荐）</li>
 *   <li>自然语言学习总结</li>
 * </ul>
 *
 * <p>评分依据（三维评估模型）：
 * <ol>
 *   <li><b>原始正确率</b>：correctQuestions / totalQuestions</li>
 *   <li><b>艾宾浩斯衰减</b>：effectiveProficiency = rawProficiency × e^(-daysSince/halfLife)</li>
 *   <li><b>置信度</b>：1 - e^(-0.4 × totalQuestions)，仅基于答题数，不受时间影响</li>
 * </ol>
 * 综合评分 = avgEffectiveProficiency × 0.6 + avgConfidence × 0.4
 */
@Service
public class LearningReportService {

    private static final Logger log = LoggerFactory.getLogger(LearningReportService.class);

    /** 有效掌握度低于此阈值视为薄弱点 */
    private static final double WEAK_THRESHOLD = 0.6;
    /** 有效掌握度高于此阈值视为强项 */
    private static final double STRONG_THRESHOLD = 0.8;
    /** 置信度低于此阈值时，掌握度数据可靠性不足 */
    private static final double LOW_CONFIDENCE = 0.5;

    private final ProficiencyService proficiencyService;
    private final ProfileService profileService;
    private final ProficiencySnapshotRepository snapshotRepo;
    private final LearningBehaviorLogRepository behaviorLogRepo;

    public LearningReportService(ProficiencyService proficiencyService,
                                  ProfileService profileService,
                                  ProficiencySnapshotRepository snapshotRepo,
                                  LearningBehaviorLogRepository behaviorLogRepo) {
        this.proficiencyService = proficiencyService;
        this.profileService = profileService;
        this.snapshotRepo = snapshotRepo;
        this.behaviorLogRepo = behaviorLogRepo;
    }

    /**
     * 生成完整学习报告。
     */
    public LearningReport generateReport(String studentId) {
        List<ProficiencyService.ProficiencyResult> allResults = proficiencyService.getAllProficiencies(studentId);
        StudentProfile profile = profileService.getProfile(studentId);

        if (allResults.isEmpty()) {
            return LearningReport.empty(studentId);
        }

        // 1. 总体统计（基于有效掌握度）
        OverallStats stats = computeOverallStats(allResults);

        // 2. 按有效掌握度排序
        List<KnowledgePointEntry> entries = buildEntries(allResults);
        entries.sort(Comparator.comparingDouble(KnowledgePointEntry::proficiency));

        // 3. 强弱项分类
        List<KnowledgePointEntry> weakPoints = entries.stream()
            .filter(e -> e.proficiency() < WEAK_THRESHOLD)
            .toList();
        List<KnowledgePointEntry> strongPoints = entries.stream()
            .filter(e -> e.proficiency() >= STRONG_THRESHOLD && e.confidence() >= LOW_CONFIDENCE)
            .toList();

        // 4. 个性化建议
        List<Recommendation> recommendations = generateRecommendations(weakPoints, entries);

        // 5. 学习总结
        String summary = generateSummary(stats, weakPoints, strongPoints);

        return new LearningReport(
            studentId, LocalDateTime.now(),
            stats, entries, weakPoints, strongPoints, recommendations, summary
        );
    }

    /**
     * 生成画像概览（供前端 Profile.vue 使用）。
     */
    public Map<String, Object> generateProfileSummary(String studentId) {
        StudentProfile profile = profileService.getProfile(studentId);
        if (profile == null) {
            return Map.of("exists", false);
        }

        List<ProficiencyService.ProficiencyResult> allResults = proficiencyService.getAllProficiencies(studentId);
        OverallStats stats = computeOverallStats(allResults);
        List<KnowledgePointEntry> entries = buildEntries(allResults);

        List<KnowledgePointEntry> weakPoints = entries.stream()
            .filter(e -> e.proficiency() < WEAK_THRESHOLD)
            .toList();
        List<KnowledgePointEntry> strongPoints = entries.stream()
            .filter(e -> e.proficiency() >= STRONG_THRESHOLD && e.confidence() >= LOW_CONFIDENCE)
            .toList();

        long learningDays = 0;
        if (profile.getCreatedAt() != null) {
            learningDays = Math.max(1, ChronoUnit.DAYS.between(profile.getCreatedAt(), LocalDateTime.now()));
        }

        Map<String, Double> confidences = new LinkedHashMap<>();
        confidences.put("knowledge", toDouble(profile.getKnowledgeBaseConfidence()));
        confidences.put("cognitive", toDouble(profile.getCognitiveStyleConfidence()));
        confidences.put("error", toDouble(profile.getErrorPatternConfidence()));
        confidences.put("pace", toDouble(profile.getLearningPaceConfidence()));
        confidences.put("goal", toDouble(profile.getGoalOrientationConfidence()));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("exists", true);
        result.put("studentId", studentId);
        result.put("learningDays", learningDays);
        result.put("resourceCount", 0);
        result.put("quizCount", stats.totalQuestions());
        result.put("compositeScore", stats.compositeScore());
        result.put("knowledgeBaseLevel", profile.getKnowledgeBaseLevel());
        result.put("cognitiveStyleType", profile.getCognitiveStyleType());
        result.put("errorPatternTags", profile.getErrorPatternTags());
        result.put("learningPaceType", profile.getLearningPaceType());
        result.put("contentPreferenceType", profile.getContentPreferenceType());
        result.put("goalOrientationType", profile.getGoalOrientationType());
        result.put("confidences", confidences);
        result.put("details", List.of(
            Map.of("label", "知识掌握", "value", stats.averageProficiencyPercent(), "color", "#667eea"),
            Map.of("label", "练习正确率", "value", stats.overallAccuracyPercent(), "color", "#67c23a"),
            Map.of("label", "学习投入度", "value", computeEngagementScore(allResults), "color", "#e6a23c"),
            Map.of("label", "资源利用率", "value", 50, "color", "#f56c6c")
        ));
        result.put("weakPoints", weakPoints.stream().map(wp -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("concept", wp.concept());
            m.put("proficiency", wp.proficiency());
            m.put("confidence", wp.confidence());
            m.put("totalQuestions", wp.totalQuestions());
            m.put("correctQuestions", wp.correctQuestions());
            m.put("daysSinceStudy", wp.daysSinceStudy());
            return m;
        }).toList());
        result.put("strongPoints", strongPoints.stream().map(sp -> Map.of(
            "concept", sp.concept(),
            "proficiency", sp.proficiency(),
            "confidence", sp.confidence()
        )).toList());
        result.put("summary", generateSummary(stats, weakPoints, strongPoints));
        result.put("knowledgeRadar", buildKnowledgeRadar(studentId));
        result.put("learningProgress", buildLearningProgress(studentId));
        result.put("learningInput", buildLearningInput(studentId));
        result.put("growthTrend", buildGrowthTrend(studentId));

        return result;
    }

    // ======================== 新增：可视化数据 ========================

    private List<Map<String, Object>> buildKnowledgeRadar(String studentId) {
        List<ProficiencyService.ProficiencyResult> all = proficiencyService.getAllProficiencies(studentId);
        if (all.isEmpty()) return List.of();

        List<ProficiencyService.ProficiencyResult> sorted = all.stream()
            .sorted(Comparator.comparingInt(ProficiencyService.ProficiencyResult::totalQuestions).reversed())
            .limit(8)
            .toList();

        Set<String> included = new HashSet<>();
        sorted.forEach(r -> included.add(r.concept()));
        List<ProficiencyService.ProficiencyResult> weak = all.stream()
            .filter(r -> !included.contains(r.concept()))
            .sorted(Comparator.comparingDouble(ProficiencyService.ProficiencyResult::effectiveProficiency))
            .limit(4)
            .toList();

        List<Map<String, Object>> radar = new ArrayList<>();
        for (var r : sorted) radar.add(Map.of("concept", r.concept(), "proficiency", round2(r.effectiveProficiency())));
        for (var r : weak) radar.add(Map.of("concept", r.concept(), "proficiency", round2(r.effectiveProficiency())));
        return radar;
    }

    private Map<String, Object> buildLearningProgress(String studentId) {
        List<ProficiencyService.ProficiencyResult> all = proficiencyService.getAllProficiencies(studentId);
        int total = all.size();
        long completed = all.stream()
            .filter(r -> r.effectiveProficiency() >= 0.8 && r.confidence() >= 0.5)
            .count();
        return Map.of("totalNodes", total, "completedNodes", (int) completed, "currentNode", "");
    }

    private Map<String, Object> buildLearningInput(String studentId) {
        long activeDays = behaviorLogRepo.countActiveDaysByUserId(studentId);
        long resourceViews = behaviorLogRepo.countByUserIdAndEventType(studentId, "RESOURCE_VIEW");
        long chatRounds = behaviorLogRepo.countByUserIdAndEventType(studentId, "CHAT_INTERACTION");
        long quizTotal = behaviorLogRepo.countByUserIdAndEventType(studentId, "QUIZ_ANSWER");

        List<Map<String, Object>> weeklyTrend = new ArrayList<>();
        LocalDate now = LocalDate.now();
        WeekFields wf = WeekFields.of(DayOfWeek.MONDAY, 1);
        for (int i = 3; i >= 0; i--) {
            LocalDate ws = now.minusWeeks(i).with(wf.dayOfWeek(), 1);
            LocalDate we = ws.plusDays(6);
            List<LearningBehaviorLog> logs = behaviorLogRepo.findByUserIdAndCreatedAtBetween(
                studentId, ws.atStartOfDay(), we.atTime(23, 59, 59));
            long wq = logs.stream().filter(l -> "QUIZ_ANSWER".equals(l.getEventType())).count();
            long wv = logs.stream().filter(l -> "RESOURCE_VIEW".equals(l.getEventType())).count();
            weeklyTrend.add(Map.of("week", "W" + (now.get(wf.weekOfYear()) - i), "quizzes", wq, "views", wv));
        }

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("activeDays", (int) activeDays);
        input.put("totalDurationMin", 0);
        input.put("resourceViews", (int) resourceViews);
        input.put("chatRounds", (int) chatRounds);
        input.put("quizTotal", (int) quizTotal);
        input.put("weeklyTrend", weeklyTrend);
        return input;
    }

    private List<Map<String, Object>> buildGrowthTrend(String studentId) {
        LocalDate today = LocalDate.now();
        List<ProficiencySnapshot> snapshots = snapshotRepo.findByStudentIdAndSnapshotDateBetween(
            studentId, today.minusWeeks(8), today);

        WeekFields wf = WeekFields.of(DayOfWeek.MONDAY, 1);
        int currentWeek = today.get(wf.weekOfYear());
        Map<Integer, List<Double>> byWeek = new LinkedHashMap<>();
        for (var s : snapshots) {
            int week = s.getSnapshotDate().get(wf.weekOfYear());
            byWeek.computeIfAbsent(week, k -> new ArrayList<>())
                .add(s.getEffectiveProficiency().doubleValue());
        }

        List<Map<String, Object>> trend = new ArrayList<>();
        for (int w = currentWeek - 7; w <= currentWeek; w++) {
            List<Double> vals = byWeek.getOrDefault(w, List.of());
            double avg = vals.isEmpty() ? 0.0 : vals.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            trend.add(Map.of("week", "W" + w, "avgProficiency", round2(avg)));
        }
        return trend;
    }

    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }

    // ======================== 私有计算方法 ========================

    private OverallStats computeOverallStats(List<ProficiencyService.ProficiencyResult> results) {
        int totalQ = results.stream().mapToInt(ProficiencyService.ProficiencyResult::totalQuestions).sum();
        int correctQ = results.stream().mapToInt(ProficiencyService.ProficiencyResult::correctQuestions).sum();

        // 使用有效掌握度（含遗忘衰减）
        double avgEffectiveProficiency = results.stream()
            .mapToDouble(ProficiencyService.ProficiencyResult::effectiveProficiency)
            .average().orElse(0);
        double avgConfidence = results.stream()
            .mapToDouble(ProficiencyService.ProficiencyResult::confidence)
            .average().orElse(0);

        // 综合评分 = 平均有效掌握度 × 0.6 + 平均置信度 × 0.4
        int compositeScore = (int) Math.round((avgEffectiveProficiency * 0.6 + avgConfidence * 0.4) * 100);

        return new OverallStats(
            results.size(), totalQ, correctQ,
            totalQ > 0 ? (double) correctQ / totalQ : 0,
            avgEffectiveProficiency, avgConfidence, compositeScore
        );
    }

    private List<KnowledgePointEntry> buildEntries(List<ProficiencyService.ProficiencyResult> results) {
        return results.stream()
            .map(r -> new KnowledgePointEntry(
                r.concept(),
                r.effectiveProficiency(),   // 使用衰减后的有效掌握度
                r.confidence(),
                r.totalQuestions(),
                r.correctQuestions(),
                r.daysSinceStudy()
            ))
            .toList();
    }

    private List<Recommendation> generateRecommendations(
            List<KnowledgePointEntry> weakPoints,
            List<KnowledgePointEntry> allEntries) {

        List<Recommendation> recs = new ArrayList<>();

        for (KnowledgePointEntry wp : weakPoints) {
            if (wp.totalQuestions() == 0) {
                recs.add(new Recommendation("START", wp.concept(),
                    "「" + wp.concept() + "」尚未开始练习，建议从基础概念入手"));
            } else if (wp.proficiency() < 0.33) {
                recs.add(new Recommendation("REVIEW", wp.concept(),
                    "「" + wp.concept() + "」掌握度极低（" + fmtPct(wp.proficiency()) + "），建议系统复习并重新练习"));
            } else if (wp.daysSinceStudy() > 14) {
                recs.add(new Recommendation("REVIEW", wp.concept(),
                    "「" + wp.concept() + "」已 " + wp.daysSinceStudy() + " 天未复习，掌握度已衰减至 " + fmtPct(wp.proficiency()) + "，建议重新温习"));
            } else {
                recs.add(new Recommendation("PRACTICE", wp.concept(),
                    "「" + wp.concept() + "」掌握度偏低（" + fmtPct(wp.proficiency()) + "），建议针对性加强练习"));
            }
        }

        if (weakPoints.isEmpty() && !allEntries.isEmpty()) {
            recs.add(new Recommendation("ADVANCE", "",
                "所有已练习知识点掌握良好，建议挑战更高难度的内容"));
        }

        if (allEntries.isEmpty()) {
            recs.add(new Recommendation("START", "",
                "还没有学习数据，建议先完成一次练习来建立学习档案"));
        }

        return recs;
    }

    private String generateSummary(OverallStats stats, List<KnowledgePointEntry> weakPoints,
                                    List<KnowledgePointEntry> strongPoints) {
        if (stats.totalKnowledgePoints() == 0) {
            return "还没有学习记录。完成首次练习后，系统将自动生成学习报告，分析你的知识掌握情况。";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("共练习了 ").append(stats.totalKnowledgePoints()).append(" 个知识点，")
          .append("答题 ").append(stats.totalQuestions()).append(" 道，")
          .append("整体正确率 ").append(fmtPct(stats.overallAccuracy())).append("。");

        if (!strongPoints.isEmpty()) {
            sb.append("掌握较好的知识点：");
            for (int i = 0; i < Math.min(3, strongPoints.size()); i++) {
                sb.append(strongPoints.get(i).concept());
                if (i < Math.min(3, strongPoints.size()) - 1) sb.append("、");
            }
            sb.append("。");
        }

        if (!weakPoints.isEmpty()) {
            sb.append("需要加强的知识点：");
            for (int i = 0; i < Math.min(3, weakPoints.size()); i++) {
                KnowledgePointEntry wp = weakPoints.get(i);
                sb.append(wp.concept());
                if (wp.daysSinceStudy() > 7) sb.append("(").append(wp.daysSinceStudy()).append("天未复习)");
                if (i < Math.min(3, weakPoints.size()) - 1) sb.append("、");
            }
            sb.append("。建议优先复习这些内容。");
        }

        return sb.toString();
    }

    private int computeEngagementScore(List<ProficiencyService.ProficiencyResult> results) {
        if (results.isEmpty()) return 0;
        long recent = results.stream()
            .filter(r -> r.daysSinceStudy() <= 7)
            .count();
        return (int) Math.min(100, Math.round((double) recent / results.size() * 100));
    }

    private double toDouble(BigDecimal bd) {
        return bd != null ? bd.doubleValue() : 0.0;
    }

    private String fmtPct(double v) {
        return String.format("%.0f%%", v * 100);
    }

    // ======================== 数据类型 ========================

    public record OverallStats(
        int totalKnowledgePoints,
        int totalQuestions,
        int correctQuestions,
        double overallAccuracy,
        double averageProficiency,
        double averageConfidence,
        int compositeScore
    ) {
        public int overallAccuracyPercent() { return (int) Math.round(overallAccuracy * 100); }
        public int averageProficiencyPercent() { return (int) Math.round(averageProficiency * 100); }
    }

    public record KnowledgePointEntry(
        String concept,
        double proficiency,
        double confidence,
        int totalQuestions,
        int correctQuestions,
        long daysSinceStudy
    ) {}

    public record Recommendation(String type, String concept, String message) {}

    public record LearningReport(
        String studentId,
        LocalDateTime generatedAt,
        OverallStats overallStats,
        List<KnowledgePointEntry> allEntries,
        List<KnowledgePointEntry> weakPoints,
        List<KnowledgePointEntry> strongPoints,
        List<Recommendation> recommendations,
        String summary
    ) {
        public static LearningReport empty(String studentId) {
            return new LearningReport(
                studentId, LocalDateTime.now(),
                new OverallStats(0, 0, 0, 0, 0, 0, 0),
                List.of(), List.of(), List.of(), List.of(),
                "还没有学习记录。完成首次练习后，系统将自动生成学习报告。"
            );
        }
    }
}
