package org.example.educatorweb.topicpush.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "push_result", indexes = {
    @Index(name = "idx_pr_user_created", columnList = "userId,createdAt")
})
public class PushResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", length = 64, nullable = false)
    private String userId;

    @Column(name = "trigger_type", length = 20, nullable = false)
    private String triggerType;

    @Column(name = "resources", columnDefinition = "JSON", nullable = false)
    private String resources;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public PushResult() {}

    public PushResult(String userId, String triggerType, String resources) {
        this.userId = userId;
        this.triggerType = triggerType;
        this.resources = resources;
    }

    @PrePersist
    void prePersist() {
        if (this.createdAt == null) this.createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getTriggerType() { return triggerType; }
    public void setTriggerType(String triggerType) { this.triggerType = triggerType; }
    public String getResources() { return resources; }
    public void setResources(String resources) { this.resources = resources; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
