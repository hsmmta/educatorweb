package org.example.educatorweb.knowledgegraph.repository;

import org.example.educatorweb.knowledgegraph.model.KnowledgePoint;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface KnowledgePointRepository extends Neo4jRepository<KnowledgePoint, String> {

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
}
