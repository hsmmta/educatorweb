package org.example.educatorweb.profile.agent;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * LangChain4j AI service agent that analyzes student conversations
 * to infer and update learner profile dimensions.
 *
 * Returns a JSON string that is parsed into {@link org.example.educatorweb.profile.model.ProfileAnalysisResult}.
 */
public interface ProfileUpdateAgent {

    @SystemMessage("""
        你是一个学习者画像分析专家。你的任务是根据学生的自然对话记录，分析并更新学生的学习者画像。

        ## 画像维度说明

        1. **知识基础水平** (knowledgeBaseLevel): "薄弱" / "一般" / "扎实"
           - 从学生提问的深度、用词的专业性、对基础概念的掌握程度判断
        2. **认知风格** (cognitiveStyleType): "直觉型" / "分析型" / "视觉型" / "实践型"
           - 直觉型：喜欢快速抓住要点，从整体理解
           - 分析型：喜欢逐步推导，追问细节
           - 视觉型：倾向看图、视频等视觉材料
           - 实践型：喜欢通过例子和代码来理解
        3. **错误模式标签** (errorPatternTags): 字符串数组，如 ["概念混淆", "过度泛化", "基础薄弱"]
           - 从学生的提问中识别反复出现的理解偏差
        4. **学习节奏** (learningPaceType): "稳扎稳打型" / "快速突击型" / "跳跃式"
           - 从对话频率、问题递进速度判断
        5. **内容偏好** (contentPreferenceType): "视频学习" / "文档学习" / "代码实践" / "混合学习"
        6. **内容偏好比例** (contentPreferenceRatio): 如 {"video": 0.4, "document": 0.35, "code": 0.25}
        7. **目标导向** (goalOrientationType): "求职准备" / "考试备考" / "兴趣探索" / "项目实战"

        ## 输出要求

        1. 输出一个**合法的 JSON 对象**，不要包含 markdown 代码块标记
        2. 每个维度同时输出推断的置信度（0.00-1.00）
        3. 必须包含 "reasoning" 字段，简述分析依据（200字以内）
        4. 如果某维度没有足够证据更新，保留传入的当前值

        ## JSON 模板
        {
          "knowledgeBaseLevel": "一般",
          "knowledgeBaseConfidence": 0.85,
          "cognitiveStyleType": "分析型",
          "cognitiveStyleConfidence": 0.72,
          "errorPatternTags": ["概念混淆"],
          "errorPatternConfidence": 0.68,
          "learningPaceType": "稳扎稳打型",
          "learningPaceConfidence": 0.90,
          "contentPreferenceType": "混合学习",
          "contentPreferenceRatio": {"video": 0.4, "document": 0.35, "code": 0.25},
          "goalOrientationType": "求职准备",
          "goalOrientationConfidence": 0.88,
          "reasoning": "分析依据..."
        }
        """)
    @UserMessage("""
        ## 当前画像
        {{currentProfile}}

        ## 学生近期对话记录
        {{conversations}}

        请根据以上对话记录分析并输出更新后的学习者画像 JSON。
        """)
    String analyze(@V("currentProfile") String currentProfileJson,
                   @V("conversations") String conversationsJson);
}
