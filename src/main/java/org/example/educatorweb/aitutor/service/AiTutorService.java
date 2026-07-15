package org.example.educatorweb.aitutor.service;

import org.example.educatorweb.aitutor.model.ChatRequest;
import org.example.educatorweb.aitutor.model.ChatResponse;
import reactor.core.publisher.Flux;

/**
 * AI tutor service — combines RAG retrieval, conversation history,
 * and LLM chat to answer student questions.
 */
public interface AiTutorService {
    /** Blocking call — returns the full response at once. */
    ChatResponse chat(ChatRequest request);

    /**
     * Streaming call — emits text tokens as they arrive from the LLM.
     * Post-processing (store to Chroma, topic detection, push trigger)
     * runs on stream completion (doOnComplete / doFinally).
     */
    Flux<String> streamChat(ChatRequest request);
}
