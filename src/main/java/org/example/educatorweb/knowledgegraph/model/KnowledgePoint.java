package org.example.educatorweb.knowledgegraph.model;

import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Relationship;

import java.util.HashSet;
import java.util.Set;

@Node("KnowledgePoint")
public class KnowledgePoint {

    @Id
    private String id;           // "svm", "linear_regression"

    private String name;         // "支持向量机"
    private String category;     // 数学基础 | 概念 | 算法 | 应用 | 工具
    private int difficulty;      // 1-5
    private String description;  // one-sentence summary

    @Relationship(type = "REQUIRES", direction = Relationship.Direction.OUTGOING)
    private Set<KnowledgePoint> prerequisites = new HashSet<>();

    @Relationship(type = "RELATED_TO", direction = Relationship.Direction.OUTGOING)
    private Set<KnowledgePoint> relatedConcepts = new HashSet<>();

    /** Composite KP containing sub-knowledge-points (e.g. "监督学习" → "线性回归", "SVM"). */
    @Relationship(type = "CONTAINS", direction = Relationship.Direction.OUTGOING)
    private Set<KnowledgePoint> subPoints = new HashSet<>();

    /** Course this knowledge point belongs to. */
    @Relationship(type = "BELONGS_TO", direction = Relationship.Direction.OUTGOING)
    private Course course;

    /** Learning resources (textbooks, videos, exercises) for this knowledge point. */
    @Relationship(type = "HAS_RESOURCE", direction = Relationship.Direction.OUTGOING)
    private Set<LearningResource> resources = new HashSet<>();

    public KnowledgePoint() {}

    public KnowledgePoint(String id, String name, String category, int difficulty,
                          String description) {
        this.id = id;
        this.name = name;
        this.category = category;
        this.difficulty = difficulty;
        this.description = description;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public int getDifficulty() { return difficulty; }
    public void setDifficulty(int difficulty) { this.difficulty = difficulty; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Set<KnowledgePoint> getPrerequisites() { return prerequisites; }
    public void setPrerequisites(Set<KnowledgePoint> prerequisites) { this.prerequisites = prerequisites; }
    public Set<KnowledgePoint> getRelatedConcepts() { return relatedConcepts; }
    public void setRelatedConcepts(Set<KnowledgePoint> relatedConcepts) { this.relatedConcepts = relatedConcepts; }
    public Set<KnowledgePoint> getSubPoints() { return subPoints; }
    public void setSubPoints(Set<KnowledgePoint> subPoints) { this.subPoints = subPoints; }
    public Course getCourse() { return course; }
    public void setCourse(Course course) { this.course = course; }
    public Set<LearningResource> getResources() { return resources; }
    public void setResources(Set<LearningResource> resources) { this.resources = resources; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof KnowledgePoint that)) return false;
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return id != null ? id.hashCode() : 0;
    }
}
