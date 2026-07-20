package org.example.educatorweb.profile.agent;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

import java.util.List;

/**
 * LLM agent that analyzes weak proficiency concepts and classifies them
 * into cognitive error pattern types (概念混淆, 基础薄弱, etc.).
 */
public interface ErrorPatternAgent {

    @SystemMessage("""
        你是一个学习诊断专家。根据学生的答题薄弱点数据，推断其认知层面的错误模式。

        ## 错误模式标签（从中选择 2-4 个最匹配的）
        概念混淆、基础薄弱、过度泛化、计算错误、逻辑错误、
        记忆遗忘、应用不当、粗心大意、知识盲区、术语混淆

        ## 输出要求
        只输出一个 JSON 字符串数组，不要任何其他文字。
        例如：["概念混淆", "基础薄弱"]
        """)
    @UserMessage("""
        ## 学生薄弱知识点（掌握度 < 60%）
        {{weakPoints}}
        """)
    String analyze(@V("weakPoints") String weakPointsDesc);
}
