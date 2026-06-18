package org.example.educatorweb.aitutor.service;

import org.example.educatorweb.aitutor.model.ChatRequest;
import org.example.educatorweb.aitutor.model.ChatResponse;

/**
 * AI tutor service — combines RAG retrieval, conversation history,
 * and LLM chat to answer student questions.
 */
public interface AiTutorService {
    ChatResponse chat(ChatRequest request);
}
