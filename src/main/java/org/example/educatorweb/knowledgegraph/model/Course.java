package org.example.educatorweb.knowledgegraph.model;

import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Relationship;

import java.util.HashSet;
import java.util.Set;

/**
 * Online course entity in the knowledge graph.
 * Connected to KnowledgePoints via CONTAINS_KNOWLEDGE relationship,
 * and to other Courses via PREREQUISITE / PARALLEL.
 */
@Node("Course")
public class Course {

    @Id
    private String id;           // "ml_course_zju"

    private String name;         // "机器学习_浙江大学"
    private String institution;  // "浙江大学"
    private String duration;     // 短期 | 中期 | 长期
    private String type;         // 理论 | 实践
    private double rating;       // 0.0 - 5.0
    private String description;  // course summary

    @Relationship(type = "CONTAINS_KNOWLEDGE", direction = Relationship.Direction.OUTGOING)
    private Set<KnowledgePoint> knowledgePoints = new HashSet<>();

    @Relationship(type = "PREREQUISITE", direction = Relationship.Direction.OUTGOING)
    private Set<Course> prerequisites = new HashSet<>();

    @Relationship(type = "PARALLEL", direction = Relationship.Direction.OUTGOING)
    private Set<Course> parallelCourses = new HashSet<>();

    public Course() {}

    public Course(String id, String name, String institution, String duration,
                  String type, double rating, String description) {
        this.id = id;
        this.name = name;
        this.institution = institution;
        this.duration = duration;
        this.type = type;
        this.rating = rating;
        this.description = description;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getInstitution() { return institution; }
    public void setInstitution(String institution) { this.institution = institution; }
    public String getDuration() { return duration; }
    public void setDuration(String duration) { this.duration = duration; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public double getRating() { return rating; }
    public void setRating(double rating) { this.rating = rating; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Set<KnowledgePoint> getKnowledgePoints() { return knowledgePoints; }
    public Set<Course> getPrerequisites() { return prerequisites; }
    public Set<Course> getParallelCourses() { return parallelCourses; }
}
