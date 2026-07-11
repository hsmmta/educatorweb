package org.example.educatorweb.aitutor.service;

import org.example.educatorweb.aitutor.config.ChromaClient;
import org.example.educatorweb.aitutor.model.ChatRequest;
import org.example.educatorweb.aitutor.model.ChatResponse;
import org.example.educatorweb.aitutor.search.WebSearchService;
import org.example.educatorweb.aitutor.search.WebSearchService.SearchResult;
import org.example.educatorweb.knowledgegraph.KnowledgeGraphService;
import org.example.educatorweb.knowledgegraph.model.KnowledgeContext;
import org.example.educatorweb.profile.ProfileUpdateTrigger;
import org.example.educatorweb.rag.RagService;
import org.example.educatorweb.rag.model.DocumentSnippet;
import org.example.educatorweb.rag.service.EmbeddingService;
import org.example.educatorweb.resourcegen.config.ModelRegistry;
import org.example.educatorweb.resourcegen.infrastructure.ModelProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AiTutorServiceImpl implements AiTutorService {

    private static final Logger log = LoggerFactory.getLogger(AiTutorServiceImpl.class);

    private static final int RAG_TOP_K = 5;
    private static final int HISTORY_TOP_K = 4;
    private static final int WEB_TOP_K = 3;
    /** Threshold: launch web search when RAG snippet count is below this */
    private static final int WEB_FALLBACK_THRESHOLD = 2;

    private final ModelRegistry modelRegistry;
    private final RagService ragService;
    private final EmbeddingService embeddingService;
    private final ChromaClient chromaClient;
    private final KnowledgeGraphService kgService;
    private final WebSearchService webSearchService;
    private final ConversationMemoryService memoryService;
    private final ProfileUpdateTrigger profileUpdateTrigger;

    public AiTutorServiceImpl(ModelRegistry modelRegistry,
                              RagService ragService,
                              EmbeddingService embeddingService,
                              ChromaClient chromaClient,
                              KnowledgeGraphService kgService,
                              WebSearchService webSearchService,
                              ConversationMemoryService memoryService,
                              ProfileUpdateTrigger profileUpdateTrigger) {
        this.modelRegistry = modelRegistry;
        this.ragService = ragService;
        this.embeddingService = embeddingService;
        this.chromaClient = chromaClient;
        this.kgService = kgService;
        this.webSearchService = webSearchService;
        this.memoryService = memoryService;
        this.profileUpdateTrigger = profileUpdateTrigger;
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

        // 1. RAG — retrieve from private think-tank (Qdrant)
        List<DocumentSnippet> ragSnippets = retrieveKnowledge(studentId, question);

        // 2. KG — query the local knowledge graph (Neo4j) for structural context
        KnowledgeContext kgContext = queryKnowledgeGraph(question);

        // 3. Web — fallback internet search when private think-tank is sparse
        List<SearchResult> webResults = List.of();
        if (ragSnippets.size() < WEB_FALLBACK_THRESHOLD) {
            webResults = searchWeb(question);
        }

        // 4. Sliding-window conversation memory (recent N full text + old summaries)
        String memoryContext = memoryService.buildMemoryContext(studentId);

        // 5. Build the prompt
        String prompt = buildPrompt(question, ragSnippets, kgContext, webResults, memoryContext);

        // 6. Call the LLM
        String answer = callLlm(prompt);

        // 7. Store this round in Chroma
        storeConversation(conversationId, studentId, question, answer);

        // 8. Track conversation round → may trigger profile update
        profileUpdateTrigger.onInteraction(studentId);

        // 9. Build response
        List<ChatResponse.SourceSnippet> sources = ragSnippets.stream()
            .map(s -> new ChatResponse.SourceSnippet(s.content(), s.source(), s.score()))
            .toList();

        return new ChatResponse(conversationId, answer, sources, Instant.now());
    }

    // ---------------------------------------------------------------
    // Private helpers
    // ---------------------------------------------------------------

    private List<DocumentSnippet> retrieveKnowledge(String studentId, String question) {
        try {
            List<DocumentSnippet> snippets = ragService.retrieve(studentId, question, RAG_TOP_K);
            log.debug("AiTutor: RAG returned {} snippets", snippets.size());
            return snippets;
        } catch (Exception e) {
            log.warn("AiTutor: RAG retrieval failed: {}", e.getMessage());
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
    // Additional retrieval sources (KG + Web)
    // ---------------------------------------------------------------

    private KnowledgeContext queryKnowledgeGraph(String question) {
        try {
            KnowledgeContext ctx = kgService.queryContext(question);
            log.debug("AiTutor: KG returned prerequisites={}, successors={}, related={}",
                ctx.prerequisites().size(), ctx.successors().size(), ctx.relatedConcepts().size());
            return ctx;
        } catch (Exception e) {
            log.warn("AiTutor: KG query failed: {}", e.getMessage());
            return new KnowledgeContext(List.of(), List.of(), List.of(), 0);
        }
    }

    private List<SearchResult> searchWeb(String question) {
        try {
            List<SearchResult> results = webSearchService.search(question, WEB_TOP_K);
            log.debug("AiTutor: web search returned {} results", results.size());
            return results;
        } catch (Exception e) {
            log.warn("AiTutor: web search failed: {}", e.getMessage());
            return List.of();
        }
    }

    // ---------------------------------------------------------------
    // Prompt building
    // ---------------------------------------------------------------

    private String buildPrompt(String question,
                               List<DocumentSnippet> ragSnippets,
                               KnowledgeContext kgContext,
                               List<SearchResult> webResults,
                               String memoryContext) {
        StringBuilder sb = new StringBuilder();

        // System prompt (updated with three-level retrieval awareness)
        sb.append("""
            你是一个专业的 AI 助教，负责帮助学生学习知识、解答疑问。

            【检索优先级说明】每次提问按以下三级检索，优先使用私有智库：
            1. 私人智库（学生上传的资料）— 最高优先级，最相关
            2. 知识图谱（课程结构化知识）— 提供前置/后继/关联知识点
            3. 互联网搜索（实时补充）— 仅在私有资料不足时启用

            回答要求：
            - 优先基于私人智库资料回答，引用出处
            - 其次参考知识图谱的结构化知识
            - 最后参考互联网搜索结果
            - 诚实说明信息来源级别
            - 使用清晰易懂的语言，适合学生的学习水平
            - 适当举例说明抽象概念
            - 回答控制在 300-800 字，简洁有料

            """);

        // Level 1: RAG (private think-tank)
        if (!ragSnippets.isEmpty()) {
            sb.append("【① 私人智库 — 学生上传的资料】\n");
            for (int i = 0; i < ragSnippets.size(); i++) {
                DocumentSnippet s = ragSnippets.get(i);
                sb.append("[").append(i + 1).append("] ").append(s.content()).append("\n");
                sb.append("    —来源: ").append(s.source()).append("\n\n");
            }
        } else {
            sb.append("【① 私人智库】未找到相关私有资料。\n\n");
        }

        // Level 2: Knowledge Graph
        if (kgContext != null && (!kgContext.prerequisites().isEmpty()
                || !kgContext.successors().isEmpty()
                || !kgContext.relatedConcepts().isEmpty())) {
            sb.append("【② 知识图谱 — 结构化课程知识】\n");
            if (!kgContext.prerequisites().isEmpty()) {
                sb.append("- 前置知识: ").append(String.join("、", kgContext.prerequisites())).append("\n");
            }
            if (!kgContext.successors().isEmpty()) {
                sb.append("- 后继知识: ").append(String.join("、", kgContext.successors())).append("\n");
            }
            if (!kgContext.relatedConcepts().isEmpty()) {
                sb.append("- 关联概念: ").append(String.join("、", kgContext.relatedConcepts())).append("\n");
            }
            if (kgContext.difficultyLevel() > 0) {
                sb.append("- 难度级别: ").append(kgContext.difficultyLevel()).append(" / 5\n");
            }
            sb.append("\n");
        }

        // Level 3: Web search (internet fallback)
        if (!webResults.isEmpty()) {
            sb.append("【③ 互联网搜索 — 实时补充资料】\n");
            for (int i = 0; i < webResults.size(); i++) {
                SearchResult r = webResults.get(i);
                sb.append("[").append(i + 1).append("] ").append(r.title()).append("\n");
                sb.append("    ").append(r.snippet()).append("\n");
                sb.append("    —链接: ").append(r.url()).append("\n\n");
            }
        }

        // Conversation memory (sliding window: recent full + old summaries)
        if (memoryContext != null && !memoryContext.isBlank()) {
            sb.append(memoryContext);
            sb.append("\n");
        }

        // Current question
        sb.append("【学生提问】\n").append(question).append("\n\n");
        sb.append("请回答：");

        return sb.toString();
    }
}
