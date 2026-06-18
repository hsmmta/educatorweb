package org.example.educatorweb.knowledgegraph.build.builder;

import org.neo4j.driver.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

public class KgNeo4jWriter {

    private static final Logger log = LoggerFactory.getLogger(KgNeo4jWriter.class);
    private final Driver neo4jDriver;

    public KgNeo4jWriter(Driver neo4jDriver) {
        this.neo4jDriver = neo4jDriver;
    }

    /** Expose raw session for course/resource writes. */
    public org.neo4j.driver.Session newSession() {
        return neo4jDriver.session();
    }

    public int writeKnowledgePoints(List<Map<String, Object>> nodes) {
        int count = 0;
        try (var session = neo4jDriver.session()) {
            for (var n : nodes) {
                String id = (String) n.get("id");
                if (id == null || id.isBlank()) continue;
                Object difficulty = n.getOrDefault("difficulty", 3);
                long diffVal = difficulty instanceof Number num ? num.longValue() : 3L;
                session.run("""
                    MERGE (n:KnowledgePoint {id: $id})
                    SET n.name = $name, n.category = $cat, n.difficulty = $diff,
                        n.description = $desc
                    """,
                    Map.of("id", id, "name", (String) n.getOrDefault("name", id),
                        "cat", (String) n.getOrDefault("category", "概念"),
                        "diff", diffVal,
                        "desc", (String) n.getOrDefault("description", "")));
                count++;
            }
        } catch (Exception e) {
            log.error("KgNeo4jWriter: write KP failed: {}", e.getMessage());
        }
        return count;
    }

    public int linkRelationships(List<Map<String, Object>> nodes) {
        int count = 0;
        try (var session = neo4jDriver.session()) {
            for (var n : nodes) {
                String id = (String) n.get("id");
                if (id == null) continue;
                @SuppressWarnings("unchecked")
                List<String> pre = (List<String>) n.getOrDefault("prerequisites", List.of());
                for (String pId : pre) {
                    session.run("""
                        MATCH (a:KnowledgePoint {id: $from}), (b:KnowledgePoint {id: $to})
                        MERGE (a)-[:REQUIRES]->(b)
                        """, Map.of("from", id, "to", pId));
                    count++;
                }
                @SuppressWarnings("unchecked")
                List<String> rel = (List<String>) n.getOrDefault("relatedConcepts", List.of());
                for (String rId : rel) {
                    session.run("""
                        MATCH (a:KnowledgePoint {id: $from}), (b:KnowledgePoint {id: $to})
                        MERGE (a)-[:RELATED_TO]->(b)
                        """, Map.of("from", id, "to", rId));
                    count++;
                }
            }
        } catch (Exception e) {
            log.error("KgNeo4jWriter: link relations failed: {}", e.getMessage());
        }
        return count;
    }

    public long countKnowledgePoints() {
        try (var session = neo4jDriver.session()) {
            var r = session.run("MATCH (n:KnowledgePoint) RETURN count(n) AS c");
            return r.hasNext() ? r.single().get("c").asLong() : 0;
        } catch (Exception e) { return 0; }
    }

    public void clearGraph() {
        try (var session = neo4jDriver.session()) {
            session.run("MATCH (n) WHERE n:KnowledgePoint OR n:Course OR n:LearningResource DETACH DELETE n");
            log.info("KgNeo4jWriter: cleared KG nodes");
        } catch (Exception e) {
            log.error("KgNeo4jWriter: clear failed: {}", e.getMessage());
        }
    }
}
