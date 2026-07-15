package org.example.educatorweb.learningpath.model;

/**
 * 推荐的学习资源。
 */
public class RecommendedResource {

    private String resourceId;
    private String title;
    private String resourceType;  // DOC / PPT / QUIZ / CODE / VIDEO / MINDMAP / HTML
    private String resourceTypeLabel; // 课程文档 / 教学PPT / 练习题库 / 代码案例 / 教学视频 / 思维导图 / 交互课件
    private String reason;        // 推荐理由
    private String icon;          // 前端展示图标
    private int priority;         // 推荐优先级 (1-10)
    private Long preGeneratedId;  // FK to PreGeneratedResource (null = not yet generated)

    public RecommendedResource() {}

    public RecommendedResource(String title, String resourceType, String reason, int priority) {
        this.title = title;
        this.resourceType = resourceType;
        this.resourceTypeLabel = mapTypeLabel(resourceType);
        this.reason = reason;
        this.priority = priority;
        this.icon = mapTypeIcon(resourceType);
    }

    public String getResourceId() { return resourceId; }
    public void setResourceId(String resourceId) { this.resourceId = resourceId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getResourceType() { return resourceType; }
    public void setResourceType(String resourceType) { this.resourceType = resourceType; }
    public String getResourceTypeLabel() { return resourceTypeLabel; }
    public void setResourceTypeLabel(String resourceTypeLabel) { this.resourceTypeLabel = resourceTypeLabel; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getIcon() { return icon; }
    public void setIcon(String icon) { this.icon = icon; }
    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }
    public Long getPreGeneratedId() { return preGeneratedId; }
    public void setPreGeneratedId(Long preGeneratedId) { this.preGeneratedId = preGeneratedId; }

    private String mapTypeLabel(String type) {
        return switch (type) {
            case "DOC" -> "课程文档";
            case "PPT" -> "教学PPT";
            case "QUIZ" -> "练习题库";
            case "CODE" -> "代码案例";
            case "VIDEO" -> "教学视频";
            case "MINDMAP" -> "思维导图";
            case "HTML" -> "交互课件";
            default -> "学习资料";
        };
    }

    private String mapTypeIcon(String type) {
        return switch (type) {
            case "DOC" -> "📄";
            case "PPT" -> "📊";
            case "QUIZ" -> "📝";
            case "CODE" -> "💻";
            case "VIDEO" -> "🎬";
            case "MINDMAP" -> "🧩";
            case "HTML" -> "🌐";
            default -> "📚";
        };
    }
}