package org.example.educatorweb.knowledgegraph.build.builder;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.educatorweb.resourcegen.infrastructure.ModelProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Set;

public class KgNodeBuilder {

    private static final Logger log = LoggerFactory.getLogger(KgNodeBuilder.class);
    private final ModelProvider llmProvider;
    private final ObjectMapper objectMapper;

    public KgNodeBuilder(ModelProvider llmProvider) {
        this.llmProvider = llmProvider;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Generate knowledge points for a specific course, using a known-point set for dedup.
     * Points already in knownPointIds should be returned with "existing":true.
     * New points are written to Neo4j by the caller.
     */
    public List<Map<String, Object>> buildNodesForCourse(String courseName, String courseId,
            Set<String> knownPointIds, List<String> refTexts) {
        String refContext = String.join("\n---\n", refTexts);
        if (refContext.length() > 3000) refContext = refContext.substring(0, 3000);

        String knownStr = knownPointIds.isEmpty() ? "(empty)" : String.join(", ", knownPointIds);

        String prompt = """
            You are a ML curriculum expert. For the course/module below, list 5-10 knowledge points.

            Course: %s (%s)
            Already-known knowledge point IDs (DO NOT recreate these, mark as "existing":true):
            %s

            Reference context from the actual course materials:
            %s

            Output a JSON array. For NEW points, include: id (English slug), name (Chinese),
            category, difficulty (1-5), description, prerequisites (known IDs only),
            relatedConcepts (known IDs only). For EXISTING points that this course also covers,
            just output: {"id":"existing_id","name":"Chinese name","existing":true}.

            Example:
            [{"id":"svm","name":"支持向量机","category":"算法","difficulty":4,
              "description":"最大间隔分类器","prerequisites":["linear_algebra"],"relatedConcepts":["kernel_method"]},
              {"id":"linear_algebra","name":"线性代数","existing":true}]

            Output ONLY the JSON array, no markdown. Maximum 10 items total.
            """.formatted(courseName, courseId, knownStr, refContext);

        String response = llmProvider.chat(prompt);
        if (response == null || response.isBlank()) return List.of();
        response = response.replaceAll("```json\\s*", "").replaceAll("```\\s*$", "").trim();

        try {
            if (response.startsWith("{")) {
                var root = objectMapper.readTree(response);
                if (root.isObject() && root.has("knowledgePoints"))
                    response = objectMapper.writeValueAsString(root.get("knowledgePoints"));
            }
            return objectMapper.readValue(response, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            log.warn("KgNodeBuilder: parse failed for {}: {}", courseName, e.getMessage());
            return List.of();
        }
    }
}
