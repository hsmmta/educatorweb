package org.example.educatorweb.knowledgegraph.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.educatorweb.knowledgegraph.model.KnowledgeContext;
import org.example.educatorweb.knowledgegraph.model.KnowledgePoint;
import org.example.educatorweb.knowledgegraph.repository.KnowledgePointRepository;
import org.example.educatorweb.resourcegen.infrastructure.ModelProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Uses DeepSeek LLM to extract knowledge context for unknown knowledge points.
 * Results are written back to Neo4j so the same query never needs LLM twice.
 */
public class LlmKnowledgeExtractor {

    private static final Logger log = LoggerFactory.getLogger(LlmKnowledgeExtractor.class);

    private final ModelProvider llmProvider;
    private final KnowledgePointRepository repo;
    private final ObjectMapper objectMapper;

    public LlmKnowledgeExtractor(ModelProvider llmProvider, KnowledgePointRepository repo) {
        this.llmProvider = llmProvider;
        this.repo = repo;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Ask DeepSeek about a knowledge point, parse the result, and persist to Neo4j.
     */
    public KnowledgeContext extract(String knowledgePointName) {
        log.info("LlmKnowledgeExtractor: asking DeepSeek about '{}'", knowledgePointName);

        String prompt = buildExtractionPrompt(knowledgePointName);
        String response = llmProvider.chat(prompt);

        if (response == null || response.isBlank()) {
            log.warn("LlmKnowledgeExtractor: empty response for '{}'", knowledgePointName);
            return emptyContext();
        }

        try {
            response = response.replaceAll("```json\\s*", "").replaceAll("```\\s*$", "").trim();
            Map<String, Object> data = objectMapper.readValue(response,
                new TypeReference<Map<String, Object>>() {});

            String id = slugify(knowledgePointName);
            String name = (String) data.getOrDefault("name", knowledgePointName);
            String category = (String) data.getOrDefault("category", "概念");
            int difficulty = data.containsKey("difficulty")
                ? ((Number) data.get("difficulty")).intValue() : 3;
            String description = (String) data.getOrDefault("description", "");
            String chapter = (String) data.getOrDefault("chapter", "");

            @SuppressWarnings("unchecked")
            List<String> prerequisiteNames = (List<String>) data.getOrDefault("prerequisites", List.of());
            @SuppressWarnings("unchecked")
            List<String> relatedNames = (List<String>) data.getOrDefault("relatedConcepts", List.of());

            // Persist node
            KnowledgePoint node = new KnowledgePoint(id, name, category, difficulty, description, chapter);
            repo.save(node);

            // Link prerequisites (create stub nodes if they don't exist)
            for (String preName : prerequisiteNames) {
                KnowledgePoint prereq = repo.findByName(preName)
                    .orElseGet(() -> repo.save(
                        new KnowledgePoint(slugify(preName), preName, "概念", 3, "", "")));
                node.getPrerequisites().add(prereq);
            }

            // Link related concepts
            for (String relName : relatedNames) {
                KnowledgePoint related = repo.findByName(relName)
                    .orElseGet(() -> repo.save(
                        new KnowledgePoint(slugify(relName), relName, "概念", 3, "", "")));
                node.getRelatedConcepts().add(related);
            }

            repo.save(node);
            log.info("LlmKnowledgeExtractor: persisted '{}' (id={}) with {} prerequisites, {} related",
                name, id, prerequisiteNames.size(), relatedNames.size());

            return new KnowledgeContext(prerequisiteNames, List.of(), relatedNames, difficulty);

        } catch (Exception e) {
            log.warn("LlmKnowledgeExtractor: failed to parse response for '{}': {}",
                knowledgePointName, e.getMessage());
            return emptyContext();
        }
    }

    private String buildExtractionPrompt(String knowledgePoint) {
        return """
            You are a Machine Learning curriculum expert. For the given knowledge point, provide:
            - name: display name
            - category: one of 数学基础, 概念, 算法, 应用, 工具
            - difficulty: 1-5
            - description: one-sentence summary
            - chapter: which ML chapter it belongs to (e.g. 监督学习, 深度学习)
            - prerequisites: list of concept names that should be learned first
            - relatedConcepts: list of related concept names

            Knowledge point: %s

            Output ONLY valid JSON, no markdown:
            {"name":"...","category":"...","difficulty":3,"description":"...","chapter":"...","prerequisites":["..."],"relatedConcepts":["..."]}
            """.formatted(knowledgePoint);
    }

    private KnowledgeContext emptyContext() {
        return new KnowledgeContext(List.of(), List.of(), List.of(), 0);
    }

    private String slugify(String name) {
        return name.toLowerCase()
            .replaceAll("[^a-z0-9\\u4e00-\\u9fff]+", "_")
            .replaceAll("^_|_$", "");
    }
}
