package org.example.educatorweb.learningpath.model;

import java.util.List;

/**
 * 学习路径中的一个节点，代表一个待学习的知识点。
 */
public class PathNode {

    private String knowledgePointId;
    private String knowledgePointName;
    private String description;
    private int difficulty;
    private String category;
    private int order;
    private PathNodeStatus status;     // PENDING / CURRENT / COMPLETED
    private String estimatedDuration;   // e.g. "2-3天"
    private List<RecommendedResource> recommendedResources;

    public PathNode() {}

    public PathNode(String knowledgePointId, String knowledgePointName, String description,
                    int difficulty, String category, int order) {
        this.knowledgePointId = knowledgePointId;
        this.knowledgePointName = knowledgePointName;
        this.description = description;
        this.difficulty = difficulty;
        this.category = category;
        this.order = order;
        this.status = PathNodeStatus.PENDING;
    }

    public String getKnowledgePointId() { return knowledgePointId; }
    public void setKnowledgePointId(String knowledgePointId) { this.knowledgePointId = knowledgePointId; }
    public String getKnowledgePointName() { return knowledgePointName; }
    public void setKnowledgePointName(String knowledgePointName) { this.knowledgePointName = knowledgePointName; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public int getDifficulty() { return difficulty; }
    public void setDifficulty(int difficulty) { this.difficulty = difficulty; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public int getOrder() { return order; }
    public void setOrder(int order) { this.order = order; }
    public PathNodeStatus getStatus() { return status; }
    public void setStatus(PathNodeStatus status) { this.status = status; }
    public String getEstimatedDuration() { return estimatedDuration; }
    public void setEstimatedDuration(String estimatedDuration) { this.estimatedDuration = estimatedDuration; }
    public List<RecommendedResource> getRecommendedResources() { return recommendedResources; }
    public void setRecommendedResources(List<RecommendedResource> recommendedResources) { this.recommendedResources = recommendedResources; }

    public enum PathNodeStatus { PENDING, CURRENT, COMPLETED }
}