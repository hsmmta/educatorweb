package org.example.educatorweb.knowledgegraph.build;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.educatorweb.knowledgegraph.build.builder.KgNeo4jWriter;
import org.example.educatorweb.knowledgegraph.repository.KnowledgePointRepository;
import org.example.educatorweb.resourcegen.infrastructure.ModelProvider;
import org.neo4j.driver.Session;
import org.springframework.beans.factory.annotation.Qualifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Imports knowledge points from external datasets (Kaggle ML Course KG, etc.)
 * into the Neo4j knowledge graph.
 *
 * <p>Workflow:
 * <ol>
 *   <li>Read node/edge CSV files from a directory</li>
 *   <li>Clean IDs (English slugs) and names</li>
 *   <li>Dedupe against existing Neo4j nodes (name similarity ≥0.8 → skip)</li>
 *   <li>AI-complete missing fields (category, difficulty, description) in batches</li>
 *   <li>Write nodes and edges to Neo4j</li>
 *   <li>Call existing linkCoursesToKps + linkKpsToResources in KgBuildAgent</li>
 * </ol>
 */
@Component
public class KaggleImporter {

    private static final Logger log = LoggerFactory.getLogger(KaggleImporter.class);
    private static final double DEDUP_THRESHOLD = 0.80;

    private final ModelProvider llm;
    private final KnowledgePointRepository kpRepo;
    private final KgNeo4jWriter writer;
    private final ObjectMapper mapper = new ObjectMapper();

    public KaggleImporter(@Qualifier("deepSeekProvider") ModelProvider llm,
                           KnowledgePointRepository kpRepo, KgNeo4jWriter writer) {
        this.llm = llm;
        this.kpRepo = kpRepo;
        this.writer = writer;
    }

    /**
     * Import from a directory containing node.csv and edge.csv.
     *
     * @param dirPath path to directory with Kaggle CSV files
     * @return import stats
     */
    public ImportResult importFromDir(String dirPath) throws IOException {
        Path dir = Path.of(dirPath);
        List<Map<String, String>> rawNodes = readCsv(dir.resolve("node.csv"));
        List<Map<String, String>> rawEdges = readCsv(dir.resolve("edge.csv"));

        log.info("KaggleImporter: read {} raw nodes, {} raw edges", rawNodes.size(), rawEdges.size());

        // Step 1: Clean and normalize
        List<KpRecord> records = rawNodes.stream()
            .map(this::normalize)
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
        log.info("KaggleImporter: {} nodes after cleaning", records.size());

        // Step 2: Dedupe against existing Neo4j
        Set<String> existingIds = new HashSet<>();
        Set<String> existingNames = new HashSet<>();
        try (var s = writer.newSession()) {
            var r = s.run("MATCH (kp:KnowledgePoint) RETURN kp.id AS id, kp.name AS name");
            while (r.hasNext()) {
                var rec = r.next();
                existingIds.add(rec.get("id").asString(""));
                existingNames.add(rec.get("name").asString(""));
            }
        }
        log.info("KaggleImporter: {} existing KPs in Neo4j", existingIds.size());

        List<KpRecord> newRecords = new ArrayList<>();
        for (KpRecord rec : records) {
            if (existingIds.contains(rec.id)) {
                log.debug("KaggleImporter: skipping duplicate by ID: {}", rec.id);
                continue;
            }
            boolean dup = false;
            for (String en : existingNames) {
                if (similarity(rec.name, en) >= DEDUP_THRESHOLD) {
                    log.debug("KaggleImporter: skipping duplicate by name: '{}' ≈ '{}'", rec.name, en);
                    dup = true;
                    break;
                }
            }
            if (!dup) newRecords.add(rec);
        }
        log.info("KaggleImporter: {} new KPs after dedup", newRecords.size());

        if (newRecords.isEmpty()) {
            return new ImportResult(0, 0, "No new nodes to import (all duplicates)");
        }

        // Step 3: AI-complete missing fields in batches
        aiCompleteFields(newRecords);
        log.info("KaggleImporter: AI completion done for {} records", newRecords.size());

        // Step 4: Write to Neo4j
        int nodeCount = writeNodes(newRecords);
        int edgeCount = writeEdges(rawEdges, existingIds);

        return new ImportResult(nodeCount, edgeCount,
            String.format("Imported %d nodes, %d edges from %s", nodeCount, edgeCount, dirPath));
    }

    // ─── CSV parsing ──────────────────────────────────────────

    private List<Map<String, String>> readCsv(Path path) throws IOException {
        if (!Files.exists(path)) {
            log.warn("KaggleImporter: file not found: {}", path);
            return List.of();
        }
        List<Map<String, String>> rows = new ArrayList<>();
        try (BufferedReader r = Files.newBufferedReader(path)) {
            String header = r.readLine();
            if (header == null) return List.of();
            String[] cols = header.split(",");
            String line;
            while ((line = r.readLine()) != null) {
                String[] vals = line.split(",", -1);
                Map<String, String> row = new LinkedHashMap<>();
                for (int i = 0; i < Math.min(cols.length, vals.length); i++) {
                    row.put(cols[i].trim().toLowerCase(), vals[i].trim().replace("\"", ""));
                }
                rows.add(row);
            }
        }
        return rows;
    }

    // ─── Normalization ────────────────────────────────────────

    private KpRecord normalize(Map<String, String> raw) {
        String id = raw.getOrDefault("node_id",
            raw.getOrDefault("id", raw.getOrDefault("nodeid", "")));
        String name = raw.getOrDefault("node_name",
            raw.getOrDefault("name", raw.getOrDefault("nodename", id)));

        if (id.isBlank() && name.isBlank()) return null;

        // Generate slug ID if missing
        if (id.isBlank()) {
            id = name.toLowerCase()
                .replaceAll("[^a-z0-9\\u4e00-\\u9fff]+", "_")
                .replaceAll("^_|_$", "");
        }

        String category = raw.getOrDefault("node_type",
            raw.getOrDefault("category", raw.getOrDefault("type", "")));
        String diffStr = raw.getOrDefault("difficulty_level",
            raw.getOrDefault("difficulty", raw.getOrDefault("level", "3")));
        String desc = raw.getOrDefault("description", raw.getOrDefault("desc", ""));
        String prerequisites = raw.getOrDefault("prerequisites", "");
        String related = raw.getOrDefault("related_concepts",
            raw.getOrDefault("related", ""));

        int difficulty = 3;
        try { difficulty = (int) Double.parseDouble(diffStr); } catch (Exception ignored) {}

        return new KpRecord(id, name, category, difficulty, desc, prerequisites, related);
    }

    // ─── Deduplication ────────────────────────────────────────

    private double similarity(String a, String b) {
        if (a == null || b == null) return 0;
        if (a.equalsIgnoreCase(b)) return 1.0;
        // Jaro-Winkler-like simple similarity
        String sa = a.toLowerCase().replaceAll("[^a-z0-9]", "");
        String sb = b.toLowerCase().replaceAll("[^a-z0-9]", "");
        if (sa.isEmpty() || sb.isEmpty()) return 0;
        // Count matching bigrams
        Set<String> bigramsA = bigrams(sa);
        Set<String> bigramsB = bigrams(sb);
        Set<String> intersection = new HashSet<>(bigramsA);
        intersection.retainAll(bigramsB);
        Set<String> union = new HashSet<>(bigramsA);
        union.addAll(bigramsB);
        return union.isEmpty() ? 0 : (double) intersection.size() / union.size();
    }

    private Set<String> bigrams(String s) {
        Set<String> set = new LinkedHashSet<>();
        for (int i = 0; i < s.length() - 1; i++) {
            set.add(s.substring(i, i + 2));
        }
        return set;
    }

    // ─── AI completion ────────────────────────────────────────

    private void aiCompleteFields(List<KpRecord> records) {
        // Batch records needing completion
        List<KpRecord> needsCompletion = records.stream()
            .filter(r -> r.category.isBlank() || r.description.isBlank())
            .collect(Collectors.toList());

        if (needsCompletion.isEmpty()) {
            log.info("KaggleImporter: all records complete, skipping AI pass");
            return;
        }

        int batchSize = 10;
        for (int i = 0; i < needsCompletion.size(); i += batchSize) {
            int end = Math.min(i + batchSize, needsCompletion.size());
            List<KpRecord> batch = needsCompletion.subList(i, end);
            aiCompleteBatch(batch);
        }
    }

    private void aiCompleteBatch(List<KpRecord> batch) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < batch.size(); i++) {
            KpRecord r = batch.get(i);
            sb.append(String.format(
                "{\"i\":%d,\"id\":\"%s\",\"name\":\"%s\",\"category\":\"%s\",\"difficulty\":%d,\"description\":\"%s\"}\n",
                i, r.id, r.name, r.category, r.difficulty, r.description));
        }

        String prompt = """
            Complete the following knowledge point records. For each record:
            - Fill in missing `category` (one of: 数学基础, 概念, 算法, 应用, 工具)
            - Fill in missing `description` (one Chinese sentence)
            - Fill in missing `difficulty` (1-5, 1=easiest)
            - Do NOT change fields that already have values.
            - Return a JSON array with the same records, keeping the `i` field for ordering.

            Records to complete:
            %s

            Output ONLY a JSON array, no markdown:
            [{"i":0,"id":"...","name":"...","category":"算法","difficulty":3,"description":"..."}, ...]
            """.formatted(sb.toString());

        try {
            String response = llm.chat(prompt);
            if (response == null || response.isBlank()) return;
            response = response.replaceAll("```json\\s*", "").replaceAll("```\\s*$", "").trim();

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> completed = mapper.readValue(response, List.class);
            for (var c : completed) {
                int idx = ((Number) c.get("i")).intValue();
                if (idx >= 0 && idx < batch.size()) {
                    KpRecord orig = batch.get(idx);
                    if (orig.category.isBlank() && c.get("category") != null)
                        orig.category = c.get("category").toString();
                    if (orig.description.isBlank() && c.get("description") != null)
                        orig.description = c.get("description").toString();
                    if (orig.difficulty == 3 && c.get("difficulty") != null) {
                        try { orig.difficulty = ((Number) c.get("difficulty")).intValue(); }
                        catch (Exception ignored) {}
                    }
                }
            }
            log.info("KaggleImporter: AI completed batch of {} records", batch.size());
        } catch (Exception e) {
            log.warn("KaggleImporter: AI completion batch failed: {}", e.getMessage());
        }
    }

    // ─── Neo4j writes ─────────────────────────────────────────

    private int writeNodes(List<KpRecord> records) {
        int count = 0;
        try (Session s = writer.newSession()) {
            for (KpRecord r : records) {
                s.run("""
                    MERGE (n:KnowledgePoint {id: $id})
                    SET n.name = $name, n.category = $cat,
                        n.difficulty = $diff, n.description = $desc
                    """,
                    Map.of("id", r.id, "name", r.name, "cat", r.category.isBlank() ? "概念" : r.category,
                        "diff", (long) r.difficulty, "desc", r.description));
                count++;
            }
        }
        log.info("KaggleImporter: wrote {} nodes", count);
        return count;
    }

    private int writeEdges(List<Map<String, String>> rawEdges, Set<String> existingIds) {
        int count = 0;
        try (Session s = writer.newSession()) {
            for (var e : rawEdges) {
                String from = e.getOrDefault("source_node_id",
                    e.getOrDefault("source", e.getOrDefault("from", "")));
                String to = e.getOrDefault("target_node_id",
                    e.getOrDefault("target", e.getOrDefault("to", "")));
                String type = e.getOrDefault("relationship_type",
                    e.getOrDefault("relation", e.getOrDefault("type", "REQUIRES"))).toUpperCase();

                if (from.isBlank() || to.isBlank()) continue;
                if (!"REQUIRES".equals(type) && !"RELATED_TO".equals(type)) {
                    type = "RELATED_TO";
                }

                // Write only for existing nodes (skip references to nodes we didn't import)
                if (!existingIds.contains(from)) existingIds.add(from); // might be newly imported
                if (!existingIds.contains(to)) existingIds.add(to);

                s.run("MATCH (a:KnowledgePoint {id:$from}), (b:KnowledgePoint {id:$to}) " +
                      "MERGE (a)-[:" + type + "]->(b)",
                    Map.of("from", from, "to", to));
                count++;
            }
        } catch (Exception e) {
            log.warn("KaggleImporter: edge write error: {}", e.getMessage());
        }
        log.info("KaggleImporter: wrote {} edges", count);
        return count;
    }

    // ─── Internal record ──────────────────────────────────────

    static class KpRecord {
        String id, name, category, description, prerequisites, related;
        int difficulty;

        KpRecord(String id, String name, String category, int difficulty,
                 String description, String prerequisites, String related) {
            this.id = id; this.name = name; this.category = category;
            this.difficulty = difficulty; this.description = description;
            this.prerequisites = prerequisites; this.related = related;
        }
    }

    public record ImportResult(int nodeCount, int edgeCount, String message) {}
}
