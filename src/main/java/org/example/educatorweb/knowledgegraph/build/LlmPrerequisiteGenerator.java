package org.example.educatorweb.knowledgegraph.build;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.educatorweb.knowledgegraph.build.builder.KgNeo4jWriter;
import org.example.educatorweb.resourcegen.infrastructure.ModelProvider;
import org.neo4j.driver.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Uses DeepSeek LLM to identify prerequisite (REQUIRES) relationships
 * for knowledge concepts that currently lack them.
 *
 * <p>Process:
 * <ol>
 *   <li>Query Neo4j for concepts with 0 outgoing REQUIRES edges</li>
 *   <li>Group by category, batch ~30 at a time</li>
 *   <li>Ask LLM to identify prerequisite pairs within each batch</li>
 *   <li>Write discovered REQUIRES edges to Neo4j</li>
 * </ol>
 */
@Component
public class LlmPrerequisiteGenerator {

    private static final Logger log = LoggerFactory.getLogger(LlmPrerequisiteGenerator.class);
    private static final int BATCH_SIZE = 30;

    private final ModelProvider llm;
    private final KgNeo4jWriter writer;
    private final ObjectMapper mapper = new ObjectMapper();

    public LlmPrerequisiteGenerator(@Qualifier("deepSeekProvider") ModelProvider llm,
                                     KgNeo4jWriter writer) {
        this.llm = llm;
        this.writer = writer;
    }

    /** Result DTO */
    public record GenResult(int conceptsChecked, int prereqsFound, String message) {}

    /**
     * Generate missing prerequisite relationships for concepts that have 0 REQUIRES edges.
     * @param limit max concepts to process (0 = unlimited)
     */
    public GenResult generate(int limit) {
        // Step 1: Get concepts with 0 REQUIRES, grouped by category
        List<List<ConceptInfo>> batches = buildBatches(limit);
        if (batches.isEmpty()) {
            return new GenResult(0, 0, "No concepts need prerequisites");
        }

        int totalPrereqs = 0;
        int totalChecked = 0;

        for (int i = 0; i < batches.size(); i++) {
            List<ConceptInfo> batch = batches.get(i);
            log.info("LLM prereq batch {}/{}: {} concepts", i + 1, batches.size(), batch.size());

            List<PrereqPair> found = askLLM(batch, i + 1, batches.size());
            int written = writePrereqs(found);
            totalPrereqs += written;
            totalChecked += batch.size();

            log.info("LLM prereq batch {}: found {} pairs, wrote {} edges",
                i + 1, found.size(), written);

            // Small delay between batches to avoid rate limiting
            if (i < batches.size() - 1) {
                try { Thread.sleep(500); } catch (InterruptedException e) { break; }
            }
        }

        return new GenResult(totalChecked, totalPrereqs,
            String.format("Checked %d concepts, found %d prerequisite relationships",
                totalChecked, totalPrereqs));
    }

    // ─── Batch building ─────────────────────────────────────────

    private List<List<ConceptInfo>> buildBatches(int limit) {
        Map<String, List<ConceptInfo>> byCategory = new LinkedHashMap<>();

        try (Session s = writer.newSession()) {
            // Concepts with 0 REQUIRES edges
            var result = s.run("""
                MATCH (kp:KnowledgePoint)
                WHERE NOT (kp)-[:REQUIRES]->(:KnowledgePoint)
                RETURN kp.id AS id, kp.name AS name, kp.category AS category
                ORDER BY kp.name
                """);

            int count = 0;
            while (result.hasNext()) {
                var row = result.next();
                String cat = row.get("category").asString("概念");
                byCategory.computeIfAbsent(cat, k -> new ArrayList<>())
                    .add(new ConceptInfo(
                        row.get("id").asString(""),
                        row.get("name").asString(""),
                        cat));
                count++;
                if (limit > 0 && count >= limit) break;
            }
            log.info("LLM prereq: {} concepts with 0 REQUIRES across {} categories",
                count, byCategory.size());
        }

        // Flatten into batches
        List<List<ConceptInfo>> batches = new ArrayList<>();
        List<ConceptInfo> current = new ArrayList<>();
        for (var entry : byCategory.entrySet()) {
            for (var ci : entry.getValue()) {
                current.add(ci);
                if (current.size() >= BATCH_SIZE) {
                    batches.add(current);
                    current = new ArrayList<>();
                }
            }
        }
        if (!current.isEmpty()) batches.add(current);

        return batches;
    }

    // ─── LLM interaction ────────────────────────────────────────

    record ConceptInfo(String id, String name, String category) {}
    record PrereqPair(String concept, String prerequisite) {}

    private List<PrereqPair> askLLM(List<ConceptInfo> batch, int batchNum, int totalBatches) {
        // Build the prompt
        StringBuilder sb = new StringBuilder();
        sb.append("你是一位机器学习教育专家。以下是 ");
        sb.append(batch.size());
        sb.append(" 个知识概念（第 ");
        sb.append(batchNum);
        sb.append("/");
        sb.append(totalBatches);
        sb.append(" 批）。\n\n");

        for (int i = 0; i < batch.size(); i++) {
            var ci = batch.get(i);
            sb.append(String.format("%d. [%s] %s (分类: %s)\n",
                i + 1, ci.id, ci.name, ci.category));
        }

        sb.append("\n请分析这些概念之间的前置依赖关系。\n");
        sb.append("如果学习概念A之前必须先理解概念B，则 B 是 A 的前置知识。\n");
        sb.append("只标记明确的前置关系，不要标记[相关]或[类似]的关系。\n\n");
        sb.append("严格按以下 JSON 数组格式输出，不要添加任何解释：\n");
        sb.append("[{\"concept\":\"概念A的id\",\"prerequisite\":\"概念B的id\"}]\n");
        sb.append("\n如果没有明确的前置关系，输出空数组 []。");

        String prompt = sb.toString();

        try {
            String response = llm.chat(prompt);
            if (response == null || response.isBlank()) return List.of();

            // Extract JSON from response
            response = response.trim();
            int jsonStart = response.indexOf('[');
            int jsonEnd = response.lastIndexOf(']');
            if (jsonStart < 0 || jsonEnd < 0) {
                log.warn("LLM prereq: no JSON array in response");
                return List.of();
            }
            String json = response.substring(jsonStart, jsonEnd + 1);

            @SuppressWarnings("unchecked")
            List<Map<String, String>> rawPairs = mapper.readValue(json, List.class);
            List<PrereqPair> pairs = new ArrayList<>();
            for (var p : rawPairs) {
                String concept = p.get("concept");
                String prereq = p.get("prerequisite");
                if (concept != null && prereq != null && !concept.equals(prereq)) {
                    pairs.add(new PrereqPair(concept, prereq));
                }
            }
            return pairs;
        } catch (Exception e) {
            log.warn("LLM prereq batch {} failed: {}", batchNum, e.getMessage());
            return List.of();
        }
    }

    // ─── Neo4j write ────────────────────────────────────────────

    private int writePrereqs(List<PrereqPair> pairs) {
        int count = 0;
        try (Session s = writer.newSession()) {
            for (var pair : pairs) {
                // Verify both concepts exist
                var check = s.run(
                    "MATCH (a:KnowledgePoint {id:$a}), (b:KnowledgePoint {id:$b}) RETURN a.id, b.id",
                    Map.of("a", pair.concept, "b", pair.prerequisite));
                if (!check.hasNext()) continue;

                // Create REQUIRES edge: concept → prerequisite
                // (concept depends on prerequisite)
                s.run(
                    "MATCH (a:KnowledgePoint {id:$a}), (b:KnowledgePoint {id:$b}) " +
                    "MERGE (a)-[:REQUIRES]->(b)",
                    Map.of("a", pair.concept, "b", pair.prerequisite));
                count++;
            }
        }
        return count;
    }
}
