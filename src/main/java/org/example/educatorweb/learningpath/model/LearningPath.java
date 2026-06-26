package org.example.educatorweb.learningpath.model;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 个性化学习路径。
 * 包含有序的学习节点列表和学习路径元信息。
 */
public class LearningPath {

    private String pathId;
    private String studentId;
    private String targetKnowledgePoint;  // 目标知识点名称
    private String title;
    private String description;
    private List<PathNode> nodes;
    private int totalNodes;
    private int completedNodes;
    private int estimatedTotalDays;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // 推送策略说明
    private List<PushStrategy> pushStrategies;

    public LearningPath() {}

    public String getPathId() { return pathId; }
    public void setPathId(String pathId) { this.pathId = pathId; }
    public String getStudentId() { return studentId; }
    public void setStudentId(String studentId) { this.studentId = studentId; }
    public String getTargetKnowledgePoint() { return targetKnowledgePoint; }
    public void setTargetKnowledgePoint(String targetKnowledgePoint) { this.targetKnowledgePoint = targetKnowledgePoint; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public List<PathNode> getNodes() { return nodes; }
    public void setNodes(List<PathNode> nodes) { this.nodes = nodes; }
    public int getTotalNodes() { return totalNodes; }
    public void setTotalNodes(int totalNodes) { this.totalNodes = totalNodes; }
    public int getCompletedNodes() { return completedNodes; }
    public void setCompletedNodes(int completedNodes) { this.completedNodes = completedNodes; }
    public int getEstimatedTotalDays() { return estimatedTotalDays; }
    public void setEstimatedTotalDays(int estimatedTotalDays) { this.estimatedTotalDays = estimatedTotalDays; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public List<PushStrategy> getPushStrategies() { return pushStrategies; }
    public void setPushStrategies(List<PushStrategy> pushStrategies) { this.pushStrategies = pushStrategies; }

    /**
     * 推送策略说明。
     */
    public record PushStrategy(String icon, String title, String description) {}
}