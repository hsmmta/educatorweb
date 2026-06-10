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
    private String chapter;      // "监督学习"

    @Relationship(type = "REQUIRES", direction = Relationship.Direction.OUTGOING)
    private Set<KnowledgePoint> prerequisites = new HashSet<>();

    @Relationship(type = "RELATED_TO", direction = Relationship.Direction.OUTGOING)
    private Set<KnowledgePoint> relatedConcepts = new HashSet<>();

    public KnowledgePoint() {}

    public KnowledgePoint(String id, String name, String category, int difficulty,
                          String description, String chapter) {
        this.id = id;
        this.name = name;
        this.category = category;
        this.difficulty = difficulty;
        this.description = description;
        this.chapter = chapter;
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
    public String getChapter() { return chapter; }
    public void setChapter(String chapter) { this.chapter = chapter; }
    public Set<KnowledgePoint> getPrerequisites() { return prerequisites; }
    public void setPrerequisites(Set<KnowledgePoint> prerequisites) { this.prerequisites = prerequisites; }
    public Set<KnowledgePoint> getRelatedConcepts() { return relatedConcepts; }
    public void setRelatedConcepts(Set<KnowledgePoint> relatedConcepts) { this.relatedConcepts = relatedConcepts; }

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
