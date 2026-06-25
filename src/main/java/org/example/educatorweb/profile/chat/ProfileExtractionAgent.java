package org.example.educatorweb.profile.chat;

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
 * 画像提取智能体。
 * 基于对话历史，调用 LLM 提取/更新 6 维学生画像。
 */
@Component
public class ProfileExtractionAgent {

    private static final Logger log = LoggerFactory.getLogger(ProfileExtractionAgent.class);
    private final DeepSeekProvider llmProvider;
    private final ObjectMapper objectMapper;

    /** 6 个画像维度定义 */
    public static final List<DimensionDef> DIMENSIONS = List.of(
        new DimensionDef("knowledge", "知识基础", "📖",
            "学生对当前学习领域的掌握程度。可选值：入门/薄弱/一般/熟练/优秀。"),
        new DimensionDef("cognitive", "认知风格", "🧩",
            "学生偏好的信息加工方式。可选值：直觉型/分析型/视觉型/言语型。"),
        new DimensionDef("error", "易错偏好", "⚠️",
            "学生容易犯错的题型或知识点类型标签列表，如[\"概念混淆\",\"计算粗心\"]。"),
        new DimensionDef("pace", "学习步调", "🏃",
            "学生的学习节奏偏好。可选值：稳扎稳打型/快速推进型/跳跃型。"),
        new DimensionDef("preference", "内容偏好", "🎯",
            "学生偏好的学习资源形式。可选类型：视频优先/文档优先/混合学习。同时给出比例(video/document/code/quiz)。"),
        new DimensionDef("goal", "目标导向", "🏆",
            "学生的学习目的。可选值：求职准备/学术深造/兴趣探索/考证通关/课程考试。")
    );

    public ProfileExtractionAgent(DeepSeekProvider llmProvider, ObjectMapper objectMapper) {
        this.llmProvider = llmProvider;
        this.objectMapper = objectMapper;
    }

    /**
     * 从对话历史中提取 6 维画像。
     */
    public ProfileExtractionResult extract(String conversationHistory,
                                           StudentProfile existingProfile,
                                           Set<String> completedDims) {
        String prompt = buildExtractionPrompt(conversationHistory, existingProfile, completedDims);
        log.info("ProfileExtractionAgent: sending extraction prompt ({} chars)", prompt.length());

        try {
            String response = llmProvider.chat(prompt);
            log.info("ProfileExtractionAgent: received response ({} chars)",
                response != null ? response.length() : 0);
            if (response != null) {
                log.debug("ProfileExtractionAgent: raw response first 300 chars: {}",
                    response.length() > 300 ? response.substring(0, 300) : response);
            }
            return parseResponse(response);
        } catch (Exception e) {
            log.error("ProfileExtractionAgent: LLM call failed", e);
            return ProfileExtractionResult.empty();
        }
    }

    /**
     * 生成下一轮提问。根据轮次循环切换维度，使用预设的高质量提问模板。
     * 不再依赖 LLM 生成问题（LLM 容易忽略维度指令，生成同质化提问）。
     *
     * @param conversationHistory 对话历史（暂未使用，保留以备后续增强）
     * @param completedDims       已完成的维度
     * @param round               当前第几轮（0-based）
     */
    public String generateNextQuestion(String conversationHistory,
                                       StudentProfile currentProfile,
                                       Set<String> completedDims,
                                       int round) {
        // 按轮次循环选择维度：round=0→knowledge, 1→cognitive, 2→error, 3→pace, 4→preference, 5→goal
        int dimCount = DIMENSIONS.size();
        DimensionDef targetDim = null;
        for (int i = 0; i < dimCount; i++) {
            int idx = (round + i) % dimCount;
            DimensionDef candidate = DIMENSIONS.get(idx);
            if (!completedDims.contains(candidate.key())) {
                targetDim = candidate;
                break;
            }
        }

        if (targetDim == null) {
            return "感谢你的分享！我已经对你的学习情况有了全面的了解。现在让我为你生成个性化的学习画像...";
        }

        // 使用预设的高质量提问，每个维度2-3个不同版本的提问随机轮换
        String question = pickQuestion(targetDim, round);
        log.info("generateNextQuestion: round={}, targetDim={}, question={}", round, targetDim.key(), question);
        return question;
    }

    /**
     * 从预设题库中选择一个问题。每个维度有多个版本，避免重复感。
     */
    private String pickQuestion(DimensionDef dim, int round) {
        // round/6 决定用哪个版本（同一个维度第二次被问到时换版本）
        int variant = round / DIMENSIONS.size();
        return switch (dim.key()) {
            case "knowledge" -> switch (variant % 3) {
                case 0 -> "先聊聊你的基础吧～在目前学的这门课里，哪些概念你觉得还挺好理解的，哪些地方觉得有点模糊？";
                case 1 -> "你之前学过哪些相关的课程或者自己看过什么资料呀？感觉学得怎么样？";
                default -> "如果给自己的知识基础打个分（入门/薄弱/一般/熟练/优秀），你觉得现在大概在哪个水平？";
            };
            case "cognitive" -> switch (variant % 3) {
                case 0 -> "学习一个新知识点的时候，你习惯先看整体框架再深入细节，还是喜欢一步步跟着推导？";
                case 1 -> "看教程的时候，你是那种先翻到目录看结构的人，还是直接从头开始看的人？";
                default -> "你觉得图表、视频、文字阅读三种方式，哪种让你理解新知识最快？";
            };
            case "error" -> switch (variant % 2) {
                case 0 -> "做题或者考试的时候，有没有哪类题目你经常出错？比如概念混淆、计算粗心、还是公式记反了？";
                default -> "回顾一下你之前的作业或测试，最常见的失分原因是什么？";
            };
            case "pace" -> switch (variant % 2) {
                case 0 -> "你平时的学习节奏是怎样的？比如喜欢把一个知识点吃透再往下走，还是快速过一遍在实践中慢慢补？";
                default -> "一周大概能投入多少时间学习？你觉得自己的进度是偏快、正常、还是喜欢慢慢来？";
            };
            case "preference" -> switch (variant % 3) {
                case 0 -> "学习新东西时，你更喜欢看视频讲解、读文档，还是直接动手写代码/做实验？";
                case 1 -> "假设要学一个新框架，你会先找视频教程还是先看官方文档？";
                default -> "视频课、文字教程、动手项目——这三种你最喜欢哪种学习方式？可以多选哦～";
            };
            case "goal" -> switch (variant % 2) {
                case 0 -> "你学这些主要是为了什么呢？找工作、考研、还是单纯感兴趣想了解一下？";
                default -> "方便透露一下你的专业和年级吗？对未来的规划是偏学术还是偏就业方向？";
            };
            default -> "能再跟我聊聊你的学习情况吗？";
        };
    }

    private String buildExtractionPrompt(String conversationHistory,
                                         StudentProfile existingProfile,
                                         Set<String> completedDims) {
        return String.format("""
            你是一个学习画像分析专家。请根据以下对话历史，分析学生的学习特征，输出结构化的6维学习画像。

            ## 6维画像定义
            - knowledge（知识基础）：入门/薄弱/一般/熟练/优秀
            - cognitive（认知风格）：直觉型/分析型/视觉型/言语型
            - error（易错偏好）：字符串数组，如["概念混淆","计算粗心","公式记错","逻辑跳跃"]
            - pace（学习步调）：稳扎稳打型/快速推进型/跳跃型
            - preference（内容偏好）：type字段为"视频优先"/"文档优先"/"混合学习"之一；ratio字段为{"video":0.4,"document":0.35,"code":0.15,"quiz":0.1}格式的对象
            - goal（目标导向）：求职准备/学术深造/兴趣探索/考证通关/课程考试

            ## 对话历史
            %s

            ## 重要规则（必须严格遵守）
            1. 仔细阅读对话历史，提取所有可以从学生话语中推断出的信息
            2. 对于**任何**能从对话中推断出信息的维度，即使信息不够充分，也请给出你的最佳估计值和相应的置信度
            3. 对于完全无法推断的维度，value设为null，confidence设为0
            4. confidence表示你对该推断的确信程度：0.3=略有线索，0.6=比较确定，0.85=非常确定
            5. **即使学生没有直接回答该维度，只要对话中有间接暗示，就应该提取并给一个合理但不高的置信度**
            6. 例如：学生说"我大三，在准备春招"→ 可以推断goal为"求职准备"，confidence约0.8
            7. 例如：学生说"我学过Python和数学"→ 可以推断knowledge为"一般"或"入门"，confidence约0.5

            ## 输出格式
            只返回一个纯JSON对象，不要markdown标记，不要解释：
            {"knowledge":{"value":"一般","confidence":0.6},"cognitive":{"value":"分析型","confidence":0.5},"error":{"value":["概念混淆"],"confidence":0.4},"pace":{"value":"稳扎稳打型","confidence":0.5},"preference":{"type":"混合学习","ratio":{"video":0.4,"document":0.3,"code":0.2,"quiz":0.1},"confidence":0.5},"goal":{"value":"求职准备","confidence":0.8}}

            现在开始分析：""",
            conversationHistory);
    }

    @SuppressWarnings("unchecked")
    private ProfileExtractionResult parseResponse(String response) {
        if (response == null || response.isBlank()) {
            log.warn("ProfileExtractionAgent: empty LLM response");
            return ProfileExtractionResult.empty();
        }
        try {
            String json = response.trim();
            // 清理各种可能的包装
            if (json.startsWith("```")) {
                json = json.replaceFirst("```json\\s*", "")
                           .replaceFirst("```\\s*$", "").trim();
            }
            // 有时 LLM 会在 JSON 前后加说明文字，尝试提取 {...}
            int braceStart = json.indexOf('{');
            int braceEnd = json.lastIndexOf('}');
            if (braceStart >= 0 && braceEnd > braceStart) {
                json = json.substring(braceStart, braceEnd + 1);
            }

            Map<String, Object> raw = objectMapper.readValue(json, Map.class);

            Map<String, DimensionValue> dimensions = new LinkedHashMap<>();
            for (DimensionDef dim : DIMENSIONS) {
                String dimKey = dim.key();
                Object dimData = raw.get(dimKey);
                if (dimData instanceof Map<?, ?> dimMap) {
                    Object val = dimMap.get("value");
                    Object conf = dimMap.get("confidence");
                    BigDecimal confidence = toBigDecimal(conf);

                    if ("preference".equals(dimKey)) {
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
                        dimensions.put(dimKey, new DimensionValue(
                            typeObj != null ? typeObj.toString() : null,
                            confidence, null, ratio));
                    } else if (val instanceof List<?> list && !list.isEmpty()) {
                        List<String> tags = new ArrayList<>();
                        for (Object item : list) tags.add(item.toString());
                        dimensions.put(dimKey, new DimensionValue(null, confidence, tags, null));
                    } else if (val instanceof String s && !s.isBlank() && !"null".equals(s)) {
                        dimensions.put(dimKey, new DimensionValue(s, confidence, null, null));
                    } else if (val instanceof List<?> list && list.isEmpty()) {
                        dimensions.put(dimKey, new DimensionValue(null, BigDecimal.ZERO, null, null));
                    } else {
                        dimensions.put(dimKey, new DimensionValue(null, BigDecimal.ZERO, null, null));
                    }
                } else {
                    dimensions.put(dimKey, new DimensionValue(null, BigDecimal.ZERO, null, null));
                }
            }

            ProfileExtractionResult result = new ProfileExtractionResult(dimensions);
            log.info("ProfileExtractionAgent: parsed result, completed dims={}",
                result.completedDimensions());
            return result;
        } catch (JsonProcessingException | ClassCastException e) {
            log.warn("ProfileExtractionAgent: failed to parse LLM response: {}", e.getMessage());
            log.warn("ProfileExtractionAgent: raw response (first 500 chars): {}",
                response.length() > 500 ? response.substring(0, 500) : response);
            return ProfileExtractionResult.empty();
        }
    }

    private BigDecimal toBigDecimal(Object val) {
        if (val instanceof Number num) {
            return BigDecimal.valueOf(num.doubleValue()).setScale(2, RoundingMode.HALF_UP);
        }
        if (val instanceof String s) {
            try { return new BigDecimal(s).setScale(2, RoundingMode.HALF_UP); }
            catch (NumberFormatException e) { return BigDecimal.ZERO; }
        }
        return BigDecimal.ZERO;
    }

    // ============ 内部类型定义 ============

    public record DimensionDef(String key, String label, String icon, String description) {}

    public record DimensionValue(
        String value,
        BigDecimal confidence,
        List<String> tags,
        Map<String, Double> ratio
    ) {}

    public record ProfileExtractionResult(Map<String, DimensionValue> dimensions) {
        public static ProfileExtractionResult empty() {
            Map<String, DimensionValue> dims = new LinkedHashMap<>();
            for (DimensionDef d : DIMENSIONS) {
                dims.put(d.key(), new DimensionValue(null, BigDecimal.ZERO, null, null));
            }
            return new ProfileExtractionResult(dims);
        }

        /**
         * 将提取结果合并到 StudentProfile 实体中。
         * 注意：只要 LLM 返回了非空值就写入（即使置信度低），
         * 这样画像可以渐进式构建，后续对话可以继续完善。
         */
        public void mergeInto(StudentProfile profile) {
            DimensionValue kv = dimensions.get("knowledge");
            if (kv != null && kv.value() != null && !kv.value().isBlank()) {
                profile.setKnowledgeBaseLevel(kv.value());
                profile.setKnowledgeBaseConfidence(kv.confidence());
            }
            DimensionValue cv = dimensions.get("cognitive");
            if (cv != null && cv.value() != null && !cv.value().isBlank()) {
                profile.setCognitiveStyleType(cv.value());
                profile.setCognitiveStyleConfidence(cv.confidence());
            }
            DimensionValue ev = dimensions.get("error");
            if (ev != null && ev.tags() != null && !ev.tags().isEmpty()) {
                profile.setErrorPatternTags(new ArrayList<>(ev.tags()));
                profile.setErrorPatternConfidence(ev.confidence());
            }
            DimensionValue pv = dimensions.get("pace");
            if (pv != null && pv.value() != null && !pv.value().isBlank()) {
                profile.setLearningPaceType(pv.value());
                profile.setLearningPaceConfidence(pv.confidence());
            }
            DimensionValue prv = dimensions.get("preference");
            if (prv != null && prv.value() != null && !prv.value().isBlank()) {
                profile.setContentPreferenceType(prv.value());
            }
            if (prv != null && prv.ratio() != null && !prv.ratio().isEmpty()) {
                profile.setContentPreferenceRatio(new LinkedHashMap<>(prv.ratio()));
            }
            DimensionValue gv = dimensions.get("goal");
            if (gv != null && gv.value() != null && !gv.value().isBlank()) {
                profile.setGoalOrientationType(gv.value());
                profile.setGoalOrientationConfidence(gv.confidence());
            }
        }

        /**
         * 返回有足够置信度的维度 key 集合（confidence >= 0.5）。
         * 降低阈值让更多维度被视为"已完成"。
         */
        public Set<String> completedDimensions() {
            Set<String> completed = new HashSet<>();
            BigDecimal threshold = new BigDecimal("0.50");
            for (var entry : dimensions.entrySet()) {
                DimensionValue dv = entry.getValue();
                if (dv.value() != null && !dv.value().isBlank()
                    && dv.confidence().compareTo(threshold) >= 0) {
                    completed.add(entry.getKey());
                }
                // error 维度：有标签即可
                if ("error".equals(entry.getKey())
                    && dv.tags() != null && !dv.tags().isEmpty()
                    && dv.confidence().compareTo(threshold) >= 0) {
                    completed.add(entry.getKey());
                }
            }
            return completed;
        }
    }
}
