package org.example.educatorweb.aitutor.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.example.educatorweb.aitutor.model.ChatResponse;

import java.util.List;
import java.util.Map;

/**
 * SSE 流式事件。三种类型：
 * <ul>
 *   <li><b>status</b>：检索状态更新（如 "正在检索私有智库..."）</li>
 *   <li><b>token</b>：LLM 输出的增量文本 token</li>
 *   <li><b>done</b>：流结束，携带 conversationId、来源片段等最终元数据</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StreamEvent(
    String type,           // "status" | "token" | "done"
    String content,        // token 文本或状态消息
    String conversationId, // done 时返回
    List<ChatResponse.SourceSnippet> sources, // done 时返回
    Map<String, Object> meta // 额外元数据（如 retrievalSteps）
) {
    public static StreamEvent status(String message) {
        return new StreamEvent("status", message, null, null, null);
    }

    public static StreamEvent token(String text) {
        return new StreamEvent("token", text, null, null, null);
    }

    public static StreamEvent done(String conversationId, List<ChatResponse.SourceSnippet> sources) {
        return new StreamEvent("done", null, conversationId, sources, null);
    }
}
