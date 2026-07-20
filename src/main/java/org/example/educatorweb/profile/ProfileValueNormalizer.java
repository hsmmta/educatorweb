package org.example.educatorweb.profile;

import java.util.List;
import java.util.Map;

/**
 * Normalizes LLM-generated profile dimension values from English to Chinese
 * before persisting to the database.
 *
 * <p>The agent prompt already asks for Chinese values, but the LLM occasionally
 * outputs English; this utility catches and translates those cases.
 */
public final class ProfileValueNormalizer {

    private ProfileValueNormalizer() {}

    // ---- knowledge base level ----
    private static final Map<String, String> KNOWLEDGE_BASE = Map.<String, String>ofEntries(
        Map.entry("needs_diagnosis", "待诊断"),
        Map.entry("beginner", "入门"), Map.entry("novice", "新手"), Map.entry("elementary", "初级"),
        Map.entry("basic", "基础"), Map.entry("intermediate", "中等"), Map.entry("moderate", "一般"),
        Map.entry("average", "一般"), Map.entry("advanced", "进阶"), Map.entry("expert", "专家"),
        Map.entry("proficient", "熟练"), Map.entry("mastery", "精通"),
        Map.entry("weak", "薄弱"), Map.entry("poor", "薄弱"),
        Map.entry("strong", "扎实"), Map.entry("solid", "扎实"),
        Map.entry("excellent", "优秀"), Map.entry("outstanding", "突出"),
        Map.entry("none", "零基础"), Map.entry("limited", "有限"),
        Map.entry("foundational", "基础"), Map.entry("developing", "发展中")
    );

    // ---- cognitive style ----
    private static final Map<String, String> COGNITIVE_STYLE = Map.<String, String>ofEntries(
        Map.entry("visual", "视觉型"), Map.entry("auditory", "听觉型"), Map.entry("kinesthetic", "动手型"),
        Map.entry("reading/writing", "读写型"), Map.entry("verbal", "语言型"), Map.entry("logical", "逻辑型"),
        Map.entry("social", "协作型"), Map.entry("solitary", "独立型"), Map.entry("multimodal", "混合型"),
        Map.entry("intuitive", "直觉型"), Map.entry("analytical", "分析型"), Map.entry("analytic", "分析型"),
        Map.entry("practical", "实践型"), Map.entry("hands-on", "实践型"),
        Map.entry("reflective", "反思型"), Map.entry("active", "活跃型"),
        Map.entry("sequential", "循序型"), Map.entry("global", "全局型"), Map.entry("holistic", "整体型"),
        Map.entry("abstract", "抽象型"), Map.entry("concrete", "具象型")
    );

    // ---- error pattern tags ----
    private static final Map<String, String> ERROR_TAGS = Map.<String, String>ofEntries(
        Map.entry("needs_diagnosis", "待诊断"),
        Map.entry("calculation error", "计算错误"), Map.entry("concept misunderstanding", "概念混淆"),
        Map.entry("careless mistake", "粗心大意"), Map.entry("logic error", "逻辑错误"),
        Map.entry("memory lapse", "记忆遗忘"), Map.entry("application error", "应用错误"),
        Map.entry("overgeneralization", "过度泛化"), Map.entry("overfitting", "过拟合"),
        Map.entry("underfitting", "欠拟合"), Map.entry("data leakage", "数据泄露"),
        Map.entry("feature engineering", "特征工程错误"), Map.entry("hyperparameter", "超参数调节问题"),
        Map.entry("gradient issue", "梯度问题"), Map.entry("vanishing gradient", "梯度消失"),
        Map.entry("exploding gradient", "梯度爆炸"), Map.entry("dimension mismatch", "维度不匹配"),
        Map.entry("loss function", "损失函数理解偏差"), Map.entry("bias-variance", "偏差方差权衡问题"),
        Map.entry("regularization", "正则化理解不足"), Map.entry("optimization", "优化器选择不当"),
        Map.entry("data preprocessing", "数据预处理问题"), Map.entry("weak foundation", "基础薄弱"),
        Map.entry("lack of practice", "缺乏练习"), Map.entry("knowledge gap", "知识盲区"),
        Map.entry("terminology confusion", "术语混淆"), Map.entry("syntax error", "语法错误")
    );

    // ---- learning pace ----
    private static final Map<String, String> LEARNING_PACE = Map.<String, String>ofEntries(
        Map.entry("slow", "稳健型"), Map.entry("slow and steady", "稳扎稳打型"),
        Map.entry("steady", "稳扎稳打型"), Map.entry("stepwise", "循序渐进型"),
        Map.entry("methodical", "有条不紊型"), Map.entry("moderate", "适中型"),
        Map.entry("moderate pace", "节奏适中型"), Map.entry("balanced", "均衡型"),
        Map.entry("fast", "快速型"), Map.entry("fast learner", "学习迅速型"),
        Map.entry("fast-paced", "快节奏型"), Map.entry("rapid", "快速突击型"),
        Map.entry("accelerated", "加速型"), Map.entry("intensive", "高强度型"),
        Map.entry("adaptive", "灵活适应型"), Map.entry("flexible", "灵活型"),
        Map.entry("self-paced", "自主型"), Map.entry("structured", "结构化型"),
        Map.entry("systematic", "系统型"), Map.entry("skipping", "跳跃式"),
        Map.entry("nonlinear", "非线性型"), Map.entry("sporadic", "间歇型"),
        Map.entry("consistent", "持续型"), Map.entry("burst learning", "突击型")
    );

    // ---- content preference ----
    private static final Map<String, String> CONTENT_PREF = Map.<String, String>ofEntries(
        Map.entry("video", "视频学习"), Map.entry("text", "文档学习"), Map.entry("reading", "文档学习"),
        Map.entry("document", "文档学习"), Map.entry("documentation", "文档学习"),
        Map.entry("interactive", "交互式学习"), Map.entry("hands-on", "代码实践"),
        Map.entry("coding", "代码实践"), Map.entry("code", "代码实践"),
        Map.entry("practice", "代码实践"), Map.entry("programming", "代码实践"),
        Map.entry("project", "项目驱动"), Map.entry("project-based", "项目驱动"),
        Map.entry("audio", "音频学习"), Map.entry("podcast", "播客学习"),
        Map.entry("lecture", "听课学习"), Map.entry("visual", "可视化学习"),
        Map.entry("tutorial", "教程学习"), Map.entry("case study", "案例分析"),
        Map.entry("theoretical", "理论学习"), Map.entry("mixed", "混合学习"),
        Map.entry("blended", "混合学习"), Map.entry("hybrid", "混合学习"),
        Map.entry("multimodal", "多模态学习"), Map.entry("collaborative", "协作学习"),
        Map.entry("self-study", "自学"), Map.entry("workshop", "工作坊")
    );

    // ---- goal orientation ----
    private static final Map<String, String> GOAL_ORIENTATION = Map.<String, String>ofEntries(
        Map.entry("exam", "考试备考"), Map.entry("examination", "考试备考"),
        Map.entry("test prep", "考试备考"), Map.entry("exam preparation", "考试备考"),
        Map.entry("certification", "考证导向"), Map.entry("certificate", "考证导向"),
        Map.entry("qualification", "资格认证"), Map.entry("job preparation", "求职准备"),
        Map.entry("job", "求职准备"), Map.entry("career", "职业发展"),
        Map.entry("career advancement", "职业发展"), Map.entry("employment", "就业导向"),
        Map.entry("internship", "实习准备"), Map.entry("interview", "面试准备"),
        Map.entry("practical", "项目实战"), Map.entry("project-based", "项目实战"),
        Map.entry("project completion", "项目完成"), Map.entry("hands-on project", "项目实战"),
        Map.entry("interest", "兴趣探索"), Map.entry("personal interest", "兴趣驱动"),
        Map.entry("hobby", "兴趣爱好"), Map.entry("curiosity", "好奇心驱动"),
        Map.entry("exploratory", "探索型"), Map.entry("academic", "学术研究"),
        Map.entry("research", "学术研究"), Map.entry("academic research", "学术研究"),
        Map.entry("skill mastery", "技能精通"), Map.entry("skill building", "技能提升"),
        Map.entry("upskilling", "技能提升"), Map.entry("reskilling", "转行学习"),
        Map.entry("knowledge expansion", "知识拓展"), Map.entry("competition", "竞赛准备"),
        Map.entry("problem solving", "解决问题"), Map.entry("course requirement", "课程要求")
    );

    // ---- public API ----

    public static String normalizeKnowledgeBase(String val) {
        return normalize(val, KNOWLEDGE_BASE);
    }

    public static String normalizeCognitiveStyle(String val) {
        return normalize(val, COGNITIVE_STYLE);
    }

    public static List<String> normalizeErrorTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) return List.of();
        return tags.stream().map(tag -> normalize(tag, ERROR_TAGS)).toList();
    }

    public static String normalizeLearningPace(String val) {
        return normalize(val, LEARNING_PACE);
    }

    public static String normalizeContentPreference(String val) {
        return normalize(val, CONTENT_PREF);
    }

    public static String normalizeGoalOrientation(String val) {
        return normalize(val, GOAL_ORIENTATION);
    }

    private static String normalize(String val, Map<String, String> map) {
        if (val == null || val.isBlank()) return val;
        // If already Chinese, return as-is
        if (containsChinese(val)) return val;
        String key = val.strip().toLowerCase();
        return map.getOrDefault(key, val);
    }

    private static boolean containsChinese(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) return true;
        }
        return false;
    }
}
