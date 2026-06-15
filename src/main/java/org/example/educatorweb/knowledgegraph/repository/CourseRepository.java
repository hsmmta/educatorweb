package org.example.educatorweb.knowledgegraph.repository;

import org.example.educatorweb.knowledgegraph.model.Course;
import org.example.educatorweb.knowledgegraph.model.KnowledgePoint;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CourseRepository extends Neo4jRepository<Course, String> {

    Optional<Course> findByName(String name);

    /** Knowledge points contained in this course. */
    @Query("MATCH (c:Course {id: $id})-[:CONTAINS_KNOWLEDGE]->(kp:KnowledgePoint) RETURN kp")
    List<KnowledgePoint> findKnowledgePoints(@Param("id") String id);

    /** Prerequisite courses. */
    @Query("MATCH (c:Course {id: $id})-[:PREREQUISITE]->(pre:Course) RETURN pre")
    List<Course> findPrerequisites(@Param("id") String id);
}
