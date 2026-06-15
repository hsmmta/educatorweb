package org.example.educatorweb.knowledgegraph.model;

import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;

/**
 * Learning material associated with a knowledge point.
 * Can be textbook, video, exercise, code example, etc.
 * Lightweight — detailed content lives in Qdrant RAG.
 */
@Node("LearningResource")
public class LearningResource {

    @Id
    private String id;           // "zhou_ml_ch6"

    private String title;        // "周志华《机器学习》第6章"
    private String type;         // TEXTBOOK | VIDEO | EXERCISE | CODE | PAPER
    private String url;          // optional link
    private String description;  // summary

    public LearningResource() {}

    public LearningResource(String id, String title, String type, String url, String description) {
        this.id = id;
        this.title = title;
        this.type = type;
        this.url = url;
        this.description = description;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}
