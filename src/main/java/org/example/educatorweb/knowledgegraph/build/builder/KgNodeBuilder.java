package org.example.educatorweb.knowledgegraph.build.builder;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.educatorweb.resourcegen.infrastructure.ModelProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

public class KgNodeBuilder {

    private static final Logger log = LoggerFactory.getLogger(KgNodeBuilder.class);
    private final ModelProvider llmProvider;
    private final ObjectMapper objectMapper;

    public KgNodeBuilder(ModelProvider llmProvider) {
        this.llmProvider = llmProvider;
        this.objectMapper = new ObjectMapper();
    }

    public List<Map<String, Object>> buildNodes(String topic, List<String> refTexts) {
        String refContext = String.join("\n---\n", refTexts);
        if (refContext.length() > 2000) refContext = refContext.substring(0, 2000);

        String prompt = """
            You are a ML curriculum expert. Generate 1-3 knowledge points for: %s

            Reference context:
            %s

            Output ONLY a JSON array:
            [{"id":"slug","name":"Chinese name","category":"算法|数学基础|概念|应用|工具",
              "difficulty":3,"description":"one sentence","prerequisites":["id1"],
              "relatedConcepts":["id2"]}]
            Output ONLY the array, no markdown.
            """.formatted(topic, refContext);

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
            log.warn("KgNodeBuilder: parse failed for {}: {}", topic, e.getMessage());
            return List.of();
        }
    }

    public List<Map<String, Object>> buildCourses(List<String> refTexts) {
        String refContext = String.join("\n---\n", refTexts);
        if (refContext.length() > 2000) refContext = refContext.substring(0, 2000);

        String prompt = """
            Generate 2-4 ML courses based on references. Include real institution names.
            References:
            %s
            Output ONLY a JSON array:
            [{"id":"slug","name":"机器学习_浙江大学","institution":"浙江大学",
              "duration":"短期|中期|长期","type":"理论|实践","rating":4.5,
              "description":"one sentence","knowledgePointIds":["kp1"],
              "prerequisiteCourseIds":[]}]
            """.formatted(refContext);

        String response = llmProvider.chat(prompt);
        if (response == null || response.isBlank()) return List.of();
        response = response.replaceAll("```json\\s*", "").replaceAll("```\\s*$", "").trim();

        try {
            if (response.startsWith("{")) {
                var root = objectMapper.readTree(response);
                if (root.has("courses"))
                    response = objectMapper.writeValueAsString(root.get("courses"));
            }
            return objectMapper.readValue(response, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            log.warn("KgNodeBuilder: course parse failed: {}", e.getMessage());
            return List.of();
        }
    }
}
