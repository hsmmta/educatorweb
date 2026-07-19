package org.example.educatorweb.knowledgegraph.repository;

import org.example.educatorweb.knowledgegraph.model.KnowledgePoint;
import org.example.educatorweb.knowledgegraph.model.LearningResource;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface KnowledgePointRepository extends Neo4jRepository<KnowledgePoint, String> {

    /**
     * Count all knowledge points (works around SDN transaction template issue in WebFlux).
     */
    @Query("MATCH (n:KnowledgePoint) RETURN count(n)")
    long countAll();

    /**
     * Find by name (generators often pass Chinese names like "支持向量机").
     */
    Optional<KnowledgePoint> findByName(String name);

    /**
     * Prerequisites: MATCH (n)-[:REQUIRES]->(p) for the given knowledge point id.
     */
    @Query("MATCH (n:KnowledgePoint {id: $id})-[:REQUIRES]->(prereq:KnowledgePoint) RETURN prereq")
    List<KnowledgePoint> findPrerequisites(@Param("id") String id);

    /**
     * Successors: nodes that REQUIRE this knowledge point.
     */
    @Query("MATCH (n:KnowledgePoint {id: $id})<-[:REQUIRES]-(succ:KnowledgePoint) RETURN succ")
    List<KnowledgePoint> findSuccessors(@Param("id") String id);

    /**
     * Related concepts (bidirectional).
     */
    @Query("MATCH (n:KnowledgePoint {id: $id})-[:RELATED_TO]-(related:KnowledgePoint) RETURN related")
    List<KnowledgePoint> findRelated(@Param("id") String id);

    /**
     * Sub-knowledge-points (composite relation).
     */
    @Query("MATCH (n:KnowledgePoint {id: $id})-[:CONTAINS]->(sub:KnowledgePoint) RETURN sub")
    List<KnowledgePoint> findSubPoints(@Param("id") String id);

    /**
     * Learning resources for this knowledge point.
     */
    @Query("MATCH (n:KnowledgePoint {id: $id})-[:HAS_RESOURCE]->(r:LearningResource) RETURN r")
    List<LearningResource> findResources(@Param("id") String id);

    /**
     * All knowledge points with category and difficulty, for the category-browse UI.
     */
    @Query("MATCH (kp:KnowledgePoint) RETURN kp.id AS id, kp.name AS name, " +
           "kp.category AS category, kp.difficulty AS difficulty " +
           "ORDER BY kp.name")
    List<KnowledgePointSummary> findAllSummaries();

    /** Lightweight knowledge point summary for the browse list. */
    record KnowledgePointSummary(String id, String name, String category, Integer difficulty) {}

    /**
     * Top N nodes by total relationship count (degree-based priority).
     * Used for graph seeding — ensures we sample from the densest part of the KG.
     */
    @Query("MATCH (kp:KnowledgePoint) " +
           "OPTIONAL MATCH (kp)-[r]-(:KnowledgePoint) " +
           "RETURN kp.id AS id, kp.name AS name, kp.category AS category, kp.difficulty AS difficulty, count(r) AS degree " +
           "ORDER BY degree DESC " +
           "LIMIT $limit")
    List<KnowledgePointSummary> findTopByDegree(@Param("limit") int limit);
}
