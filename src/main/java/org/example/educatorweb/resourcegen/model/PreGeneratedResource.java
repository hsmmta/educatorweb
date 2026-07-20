package org.example.educatorweb.resourcegen.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Persisted generated resource — actual content (not just metadata).
 * Produced by the multi-agent generation pipeline during push or path planning.
 *
 * File layout on disk:
 * <pre>
 *   generated-resources/{userId}/{pushType}/{topic}/{resourceType}/{filename}
 * </pre>
 * where pushType is one of {@code topic-push} or {@code path-push}.
 */
@Entity
@Table(name = "pre_generated_resource", indexes = {
    @Index(name = "idx_pgr_user_topic", columnList = "userId,topic"),
    @Index(name = "idx_pgr_user_status", columnList = "userId,status")
})
public class PreGeneratedResource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Request ID from the generation pipeline (set after generation starts). */
    @Column(length = 64)
    private String requestId;

    @Column(name = "user_id", length = 64, nullable = false)
    private String userId;

    @Column(length = 256, nullable = false)
    private String topic;

    @Column(name = "resource_type", length = 20, nullable = false)
    private String resourceType;

    @Column(length = 512, nullable = false)
    private String title;

    /** Text-based resource content (DOC, QUIZ, MINDMAP, CODE, HTML). */
    @Column(columnDefinition = "MEDIUMTEXT")
    private String content;

    /** File path on disk for binary resources (PPT, VIDEO). */
    @Column(length = 1024)
    private String filePath;

    /** Extra metadata as JSON. */
    @Column(columnDefinition = "JSON")
    private String metadata;

    /**
     * Push category: {@code TOPIC_PUSH} (COUNT+SCHEDULED triggers)
     * or {@code PATH_PUSH} (learning path recommendations).
     */
    @Column(name = "push_type", length = 20, nullable = false)
    private String pushType;

    @Column(length = 20, nullable = false)
    @Enumerated(EnumType.STRING)
    private ResourceStatus status = ResourceStatus.GENERATING;

    @Column(name = "error_msg", length = 1024)
    private String errorMsg;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public enum ResourceStatus {
        GENERATING, READY, FAILED
    }

    // ---- constructors ----

    public PreGeneratedResource() {}

    public PreGeneratedResource(String requestId, String userId, String topic,
                                 String resourceType, String title,
                                 String pushType) {
        this.requestId = requestId != null ? requestId : UUID.randomUUID().toString();
        this.userId = userId;
        this.topic = topic;
        this.resourceType = resourceType;
        this.title = title;
        this.pushType = pushType;
        this.status = ResourceStatus.GENERATING;
    }

    @PrePersist
    void prePersist() {
        if (this.createdAt == null) this.createdAt = LocalDateTime.now();
        if (this.updatedAt == null) this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    // ---- getters / setters ----

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getTopic() { return topic; }
    public void setTopic(String topic) { this.topic = topic; }

    public String getResourceType() { return resourceType; }
    public void setResourceType(String resourceType) { this.resourceType = resourceType; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }

    public String getMetadata() { return metadata; }
    public void setMetadata(String metadata) { this.metadata = metadata; }

    public String getPushType() { return pushType; }
    public void setPushType(String pushType) { this.pushType = pushType; }

    public ResourceStatus getStatus() { return status; }
    public void setStatus(ResourceStatus status) { this.status = status; }

    public String getErrorMsg() { return errorMsg; }
    public void setErrorMsg(String errorMsg) { this.errorMsg = errorMsg; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
