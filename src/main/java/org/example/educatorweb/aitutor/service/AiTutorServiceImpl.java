package org.example.educatorweb.aitutor.service;

import org.example.educatorweb.aitutor.config.ChromaClient;
import org.example.educatorweb.aitutor.model.ChatRequest;
import org.example.educatorweb.aitutor.model.ChatResponse;
import org.example.educatorweb.rag.RagService;
import org.example.educatorweb.rag.model.DocumentSnippet;
import org.example.educatorweb.rag.service.EmbeddingService;
import org.example.educatorweb.resourcegen.config.ModelRegistry;
import org.example.educatorweb.resourcegen.infrastructure.ModelProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AiTutorServiceImpl implements AiTutorService {

    private static final Logger log = LoggerFactory.getLogger(AiTutorServiceImpl.class);

    private static final int RAG_TOP_K = 5;
    private static final int HISTORY_TOP_K = 4;

    private final ModelRegistry modelRegistry;
    private final RagService ragService;
    private final EmbeddingService embeddingService;
    private final ChromaClient chromaClient;

    public AiTutorServiceImpl(ModelRegistry modelRegistry,
                              RagService ragService,
                              EmbeddingService embeddingService,
                              ChromaClient chromaClient) {
        this.modelRegistry = modelRegistry;
        this.ragService = ragService;
        this.embeddingService = embeddingService;
        this.chromaClient = chromaClient;
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        String studentId = request.studentId();
        String question = request.question();
        String conversationId = request.conversationId() != null
            ? request.conversationId()
            : UUID.randomUUID().toString();

        log.info("AiTutor: student={}, conversation={}, question(len={})",
            studentId, conversationId, question.length());

        // 1. RAG — retrieve relevant knowledge from the document store
        List<DocumentSnippet> ragSnippets = retrieveKnowledge(question);

        // 2. Retrieve conversation history from Chroma (semantic search)
        List<Map<String, Object>> historyRecords = retrieveHistory(question, studentId);

        // 3. Build the prompt
        String prompt = buildPrompt(question, ragSnippets, historyRecords);

        // 4. Call the LLM
        String answer = callLlm(prompt);

        // 5. Store this round in Chroma (fire-and-forget; failures are logged but don't fail the response)
        storeConversation(conversationId, studentId, question, answer);

        // 6. Build response
        List<ChatResponse.SourceSnippet> sources = ragSnippets.stream()
            .map(s -> new ChatResponse.SourceSnippet(s.content(), s.source(), s.score()))
            .toList();

        return new ChatResponse(conversationId, answer, sources, Instant.now());
    }

    // ---------------------------------------------------------------
    // Private helpers
    // ---------------------------------------------------------------

    private List<DocumentSnippet> retrieveKnowledge(String question) {
        try {
            List<DocumentSnippet> snippets = ragService.retrieve(question, RAG_TOP_K);
            log.debug("AiTutor: RAG returned {} snippets", snippets.size());
            return snippets;
        } catch (Exception e) {
            log.warn("AiTutor: RAG retrieval failed: {}", e.getMessage());
            return List.of();
        }
    }

    private List<Map<String, Object>> retrieveHistory(String question, String userId) {
        try {
            float[] raw = embeddingService.embed(question);
            if (raw.length == 0) return List.of();
            List<Float> embedding = new java.util.ArrayList<>(raw.length);
            for (float f : raw) embedding.add(f);

            List<Map<String, Object>> records = chromaClient.query(embedding, userId, HISTORY_TOP_K);
            log.debug("AiTutor: retrieved {} history records for user {}", records.size(), userId);
            // Sort by timestamp ascending (oldest first for chronological context)
            records.sort(Comparator.comparing(r -> {
                Object ts = ((Map<String, Object>) r.get("metadata")).get("timestamp");
                return ts != null ? ts.toString() : "";
            }));
            return records;
        } catch (Exception e) {
            log.warn("AiTutor: history retrieval failed: {}", e.getMessage());
            return List.of();
        }
    }

    private String callLlm(String prompt) {
        try {
            ModelProvider textProvider = modelRegistry.resolve(
                org.example.educatorweb.common.model.ResourceType.DOC);
            log.debug("AiTutor: calling LLM provider={}", textProvider.providerName());
            return textProvider.chat(prompt);
        } catch (Exception e) {
            log.error("AiTutor: LLM call failed: {}", e.getMessage());
            return "抱歉，AI 助教暂时无法回答，请稍后再试。";
        }
    }

    private void storeConversation(String conversationId, String userId,
                                   String question, String answer) {
        String ts = Instant.now().toString();

        // Store user question (embed the question text)
        storeMessage(
            conversationId + ":user:" + ts, question,
            Map.of("userId", userId, "conversationId", conversationId,
                   "timestamp", ts, "role", "user")
        );

        // Store assistant answer (embed the answer text)
        storeMessage(
            conversationId + ":assistant:" + ts, answer,
            Map.of("userId", userId, "conversationId", conversationId,
                   "timestamp", ts, "role", "assistant")
        );
    }

    private void storeMessage(String id, String text, Map<String, Object> metadata) {
        try {
            float[] embedding = embeddingService.embed(text);
            if (embedding.length == 0) return;
            // Convert float[] to List<Float> for reliable JSON serialization
            List<Float> vec = new java.util.ArrayList<>(embedding.length);
            for (float f : embedding) vec.add(f);
            chromaClient.add(id, vec, text, metadata);
        } catch (Exception e) {
            log.warn("AiTutor: failed to store message {}: {}", id, e.getMessage());
        }
    }

    // ---------------------------------------------------------------
    // Prompt building
    // ---------------------------------------------------------------

    private String buildPrompt(String question,
                               List<DocumentSnippet> ragSnippets,
                               List<Map<String, Object>> historyRecords) {
        StringBuilder sb = new StringBuilder();

        // System prompt
        sb.append("""
            你是一个专业的 AI 助教，负责帮助学生学习知识、解答疑问。
            你需要：
            - 基于提供的知识资料进行回答，引用出处
            - 如果资料不足以回答问题，诚实说明，不要编造
            - 使用清晰易懂的语言，适合学生的学习水平
            - 适当举例说明抽象概念
            - 回答控制在 300-800 字，简洁有料

            """);

        // RAG context
        if (!ragSnippets.isEmpty()) {
            sb.append("【参考资料】\n");
            for (int i = 0; i < ragSnippets.size(); i++) {
                DocumentSnippet s = ragSnippets.get(i);
                sb.append("[").append(i + 1).append("] ").append(s.content()).append("\n");
                sb.append("    —来源: ").append(s.source()).append("\n\n");
            }
        }

        // Conversation history (previous Q&A from Chroma)
        if (!historyRecords.isEmpty()) {
            sb.append("【对话历史】\n");
            for (Map<String, Object> record : historyRecords) {
                String doc = (String) record.getOrDefault("document", "");
                @SuppressWarnings("unchecked")
                Map<String, Object> meta = (Map<String, Object>) record.get("metadata");
                String role = meta != null ? String.valueOf(meta.getOrDefault("role", "")) : "";
                String prefix = role.contains("user") ? "学生" : "助教";
                sb.append(prefix).append(": ").append(doc).append("\n\n");
            }
        }

        // Current question
        sb.append("【学生提问】\n").append(question).append("\n\n");
        sb.append("请回答：");

        return sb.toString();
    }
}
