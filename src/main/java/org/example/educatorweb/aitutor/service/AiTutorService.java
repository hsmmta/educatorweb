package org.example.educatorweb.aitutor.service;

import org.example.educatorweb.aitutor.model.ChatRequest;
import org.example.educatorweb.aitutor.model.ChatResponse;
import reactor.core.publisher.Flux;

/**
 * AI tutor service — combines RAG retrieval, conversation history,
 * and LLM chat to answer student questions.
 */
public interface AiTutorService {
    ChatResponse chat(ChatRequest request);

    /** 流式问答：先推送检索状态，再 token-by-token 输出 LLM 回答 */
    Flux<StreamEvent> chatStream(ChatRequest request);
}
