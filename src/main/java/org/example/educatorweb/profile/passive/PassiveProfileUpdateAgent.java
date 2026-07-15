package org.example.educatorweb.profile.passive;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.educatorweb.profile.model.StudentProfile;
import org.example.educatorweb.resourcegen.infrastructure.DeepSeekProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Passive profile update agent (Agent 1).
 *
 * <p>Unlike the active {@code ProfileExtractionAgent}, this agent:
 * <ul>
 *   <li>Receives the current profile (with confidence) plus a single conversation slice</li>
 *   <li>Returns feature values + a "judgment" (match/conflict/new/insufficient)</li>
 *   <li>Confidence adjustment is deterministic (Java-side formula), not LLM-assigned</li>
 * </ul>
 */
@Component
public class PassiveProfileUpdateAgent {

    private static final Logger log = LoggerFactory.getLogger(PassiveProfileUpdateAgent.class);

    private static final BigDecimal MATCH_DELTA      = new BigDecimal("0.10");
    private static final BigDecimal CONFLICT_DELTA   = new BigDecimal("0.15");
    private static final BigDecimal NEW_CONFIDENCE   = new BigDecimal("0.55");
    private static final BigDecimal FLIP_CONFIDENCE  = new BigDecimal("0.15");
    private static final BigDecimal MAX_CONFIDENCE   = new BigDecimal("0.95");
    private static final BigDecimal ZERO             = BigDecimal.ZERO;
    private static final List<String> DIM_KEYS = List.of("knowledge","cognitive","error","pace","preference","goal");

    private final DeepSeekProvider llmProvider;
    private final ObjectMapper objectMapper;

    public PassiveProfileUpdateAgent(DeepSeekProvider llmProvider, ObjectMapper objectMapper) {
        this.llmProvider = llmProvider;
        this.objectMapper = objectMapper;
    }

    /**
     * Call LLM to extract features from a conversation slice and judge
     * their relationship to the current profile.
     *
     * @param profile current student profile (read-only for the prompt)
     * @param slice   the conversation segment to analyze
     * @return parsed LLM response with per-dimension value + judgment
     */
    public UpdateResult analyze(StudentProfile profile, ConversationSlicer.Slice slice) {
        String prompt = buildUpdatePrompt(profile, slice);
        log.info("PassiveProfileUpdateAgent: analyzing slice conv={} topic={} ({} chars)",
            slice.conversationId(), slice.topic(), slice.text().length());

        try {
            String response = llmProvider.chat(prompt);
            return parseResponse(response);
        } catch (Exception e) {
            log.error("PassiveProfileUpdateAgent: LLM call failed", e);
            return UpdateResult.empty();
        }
    }

    /**
     * Apply deterministic confidence adjustment to the profile based on LLM judgment.
     * Mutates the profile in place.
     */
    public void applyConfidenceAdjustment(StudentProfile profile, UpdateResult result) {
        for (String dimKey : result.dimensions().keySet()) {
            DimensionJudgment dj = result.dimensions().get(dimKey);
            if (dj == null) continue;

            switch (dimKey) {
                case "knowledge" -> applyDim(profile.getKnowledgeBaseLevel(),
                    profile::setKnowledgeBaseLevel,
                    profile::setKnowledgeBaseConfidence,
                    profile.getKnowledgeBaseConfidence(), dj);
                case "cognitive" -> applyDim(profile.getCognitiveStyleType(),
                    profile::setCognitiveStyleType,
                    profile::setCognitiveStyleConfidence,
                    profile.getCognitiveStyleConfidence(), dj);
                case "error" -> applyErrorDim(profile, dj);
                case "pace" -> applyDim(profile.getLearningPaceType(),
                    profile::setLearningPaceType,
                    profile::setLearningPaceConfidence,
                    profile.getLearningPaceConfidence(), dj);
                case "preference" -> applyPreferenceDim(profile, dj);
                case "goal" -> applyDim(profile.getGoalOrientationType(),
                    profile::setGoalOrientationType,
                    profile::setGoalOrientationConfidence,
                    profile.getGoalOrientationConfidence(), dj);
            }
        }
    }

    // ---- confidence formula ----

    private void applyDim(String currentValue,
                          java.util.function.Consumer<String> valueSetter,
                          java.util.function.Consumer<BigDecimal> confSetter,
                          BigDecimal currentConf,
                          DimensionJudgment dj) {
        String judgment = dj.judgment();
        BigDecimal conf = currentConf != null ? currentConf : ZERO;

        switch (judgment) {
            case "match" -> {
                BigDecimal newConf = conf.add(MATCH_DELTA);
                if (newConf.compareTo(MAX_CONFIDENCE) > 0) newConf = MAX_CONFIDENCE;
                confSetter.accept(newConf);
                // value unchanged
            }
            case "conflict" -> {
                BigDecimal newConf = conf.subtract(CONFLICT_DELTA);
                if (newConf.compareTo(ZERO) > 0) {
                    confSetter.accept(newConf);
                    // confidence still positive, keep old value
                } else {
                    // flipped: adopt new value with flip confidence, or reset to zero if no replacement
                    if (dj.value() != null && !dj.value().isBlank()) {
                        valueSetter.accept(dj.value());
                        confSetter.accept(FLIP_CONFIDENCE);
                    } else {
                        confSetter.accept(ZERO);
                    }
                }
            }
            case "new" -> {
                if (dj.value() != null && !dj.value().isBlank()) {
                    valueSetter.accept(dj.value());
                }
                confSetter.accept(NEW_CONFIDENCE);
            }
            // "insufficient": no change
        }
    }

    private void applyErrorDim(StudentProfile profile, DimensionJudgment dj) {
        String judgment = dj.judgment();
        BigDecimal conf = profile.getErrorPatternConfidence() != null
            ? profile.getErrorPatternConfidence() : ZERO;

        switch (judgment) {
            case "match" -> {
                BigDecimal newConf = conf.add(MATCH_DELTA);
                if (newConf.compareTo(MAX_CONFIDENCE) > 0) newConf = MAX_CONFIDENCE;
                profile.setErrorPatternConfidence(newConf);
            }
            case "conflict" -> {
                BigDecimal newConf = conf.subtract(CONFLICT_DELTA);
                if (newConf.compareTo(ZERO) > 0) {
                    profile.setErrorPatternConfidence(newConf);
                } else {
                    if (dj.tags() != null && !dj.tags().isEmpty()) {
                        profile.setErrorPatternTags(new ArrayList<>(dj.tags()));
                        profile.setErrorPatternConfidence(FLIP_CONFIDENCE);
                    } else {
                        profile.setErrorPatternConfidence(ZERO);
                    }
                }
            }
            case "new" -> {
                if (dj.tags() != null && !dj.tags().isEmpty()) {
                    profile.setErrorPatternTags(new ArrayList<>(dj.tags()));
                }
                profile.setErrorPatternConfidence(NEW_CONFIDENCE);
            }
        }
    }

    private void applyPreferenceDim(StudentProfile profile, DimensionJudgment dj) {
        String judgment = dj.judgment();
        switch (judgment) {
            case "match" -> { /* no confidence field, no-op */ }
            case "conflict", "new" -> {
                if (dj.value() != null && !dj.value().isBlank()) {
                    profile.setContentPreferenceType(dj.value());
                }
                if (dj.ratio() != null && !dj.ratio().isEmpty()) {
                    profile.setContentPreferenceRatio(new LinkedHashMap<>(dj.ratio()));
                }
            }
        }
    }

    // ---- prompt building ----

    private String buildUpdatePrompt(StudentProfile profile, ConversationSlicer.Slice slice) {
        return String.format("""
            你是一个学习画像分析专家。现在需要根据学生与AI助教的**新对话片段**，
            **增量更新**该学生的6维学习画像。

            ## 学生当前画像
            - knowledge（知识基础）：%s，置信度 %.2f
            - cognitive（认知风格）：%s，置信度 %.2f
            - error（易错偏好）：%s，置信度 %.2f
            - pace（学习步调）：%s，置信度 %.2f
            - preference（内容偏好）：%s / ratio=%s
            - goal（目标导向）：%s，置信度 %.2f

            ## 6维画像定义
            - knowledge: 入门/薄弱/一般/熟练/优秀
            - cognitive: 直觉型/分析型/视觉型/言语型
            - error: 字符串数组如["概念混淆","计算粗心"]
            - pace: 稳扎稳打型/快速推进型/跳跃型
            - preference: type字段为"视频优先"/"文档优先"/"混合学习"；ratio为{"video":0.4,"document":0.3,"code":0.2,"quiz":0.1}格式
            - goal: 求职准备/学术深造/兴趣探索/考证通关/课程考试

            ## 新对话片段（话题: %s）
            %s

            ## 任务
            对每个维度，从新对话中提取特征，并判断与当前画像的关系：
            - "match": 新证据与当前画像**一致**，互证加强
            - "conflict": 新证据与当前画像**矛盾**（值明显不同），应质疑当前画像
            - "new": 该维度之前无可靠信息（置信度<0.3或值为空），新证据是首次有效信息
            - "insufficient": 本片段中**完全无法推断**该维度

            ## 输出格式（纯JSON，不要markdown代码块）
            {"knowledge":{"value":"入门","judgment":"match"},"cognitive":{"value":"分析型","judgment":"conflict"},"error":{"value":["概念混淆"],"judgment":"match"},"pace":{"value":"稳扎稳打型","judgment":"insufficient"},"preference":{"type":"视频优先","ratio":{"video":0.6,"document":0.4},"judgment":"new"},"goal":{"value":"求职准备","judgment":"match"}}
            """,
            profile.getKnowledgeBaseLevel(), fmtConf(profile.getKnowledgeBaseConfidence()),
            profile.getCognitiveStyleType(), fmtConf(profile.getCognitiveStyleConfidence()),
            profile.getErrorPatternTags() != null ? String.join("、", profile.getErrorPatternTags()) : "无",
                fmtConf(profile.getErrorPatternConfidence()),
            profile.getLearningPaceType(), fmtConf(profile.getLearningPaceConfidence()),
            profile.getContentPreferenceType(), profile.getContentPreferenceRatio(),
            profile.getGoalOrientationType(), fmtConf(profile.getGoalOrientationConfidence()),
            slice.topic() != null ? slice.topic() : "通用辅导",
            slice.text()
        );
    }

    private double fmtConf(BigDecimal bd) {
        return bd != null ? bd.doubleValue() : 0.0;
    }

    // ---- response parsing ----

    @SuppressWarnings("unchecked")
    private UpdateResult parseResponse(String response) {
        if (response == null || response.isBlank()) return UpdateResult.empty();
        try {
            String json = response.trim();
            if (json.startsWith("```")) {
                json = json.replaceFirst("```json\\s*", "").replaceFirst("```\\s*$", "").trim();
            }
            int braceStart = json.indexOf('{');
            int braceEnd = json.lastIndexOf('}');
            if (braceStart >= 0 && braceEnd > braceStart) {
                json = json.substring(braceStart, braceEnd + 1);
            }

            Map<String, Object> raw = objectMapper.readValue(json, Map.class);
            Map<String, DimensionJudgment> dims = new LinkedHashMap<>();

            for (String dimKey : DIM_KEYS) {
                Object dimData = raw.get(dimKey);
                if (!(dimData instanceof Map<?, ?> dimMap)) {
                    dims.put(dimKey, new DimensionJudgment(null, null, null, "insufficient"));
                    continue;
                }
                String judgment = dimMap.get("judgment") != null
                    ? dimMap.get("judgment").toString() : "insufficient";

                if ("error".equals(dimKey)) {
                    Object val = dimMap.get("value");
                    List<String> tags = null;
                    if (val instanceof List<?> list && !list.isEmpty()) {
                        tags = new ArrayList<>();
                        for (Object item : list) tags.add(item.toString());
                    }
                    dims.put(dimKey, new DimensionJudgment(null, null, tags, judgment));
                } else if ("preference".equals(dimKey)) {
                    Object typeObj = dimMap.get("type");
                    Object ratioObj = dimMap.get("ratio");
                    Map<String, Double> ratio = null;
                    if (ratioObj instanceof Map<?, ?> rm) {
                        ratio = new LinkedHashMap<>();
                        for (Map.Entry<?, ?> e : rm.entrySet()) {
                            if (e.getValue() instanceof Number num) {
                                ratio.put(e.getKey().toString(), num.doubleValue());
                            }
                        }
                    }
                    dims.put(dimKey, new DimensionJudgment(
                        typeObj != null ? typeObj.toString() : null, ratio, null, judgment));
                } else {
                    Object val = dimMap.get("value");
                    String value = val instanceof String s && !s.isBlank() && !"null".equals(s)
                        ? s : null;
                    dims.put(dimKey, new DimensionJudgment(value, null, null, judgment));
                }
            }
            return new UpdateResult(dims);
        } catch (JsonProcessingException | ClassCastException e) {
            log.warn("PassiveProfileUpdateAgent: failed to parse LLM response: {}", e.getMessage());
            return UpdateResult.empty();
        }
    }

    // ---- inner types ----

    /** Per-dimension LLM output: value + judgment. */
    public record DimensionJudgment(
        String value,              // scalar value (null for error/preference)
        Map<String, Double> ratio,  // preference ratio
        List<String> tags,         // error tags
        String judgment            // match | conflict | new | insufficient
    ) {}

    /** Parsed LLM response. */
    public record UpdateResult(Map<String, DimensionJudgment> dimensions) {
        public static UpdateResult empty() {
            Map<String, DimensionJudgment> dims = new LinkedHashMap<>();
            for (String key : DIM_KEYS) {
                dims.put(key, new DimensionJudgment(null, null, null, "insufficient"));
            }
            return new UpdateResult(dims);
        }
    }
}
