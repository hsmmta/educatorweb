package org.example.educatorweb.learninglog.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "learning_behavior_log", indexes = {
    @Index(name = "idx_lbl_user", columnList = "userId"),
    @Index(name = "idx_lbl_user_concept", columnList = "userId,concept"),
    @Index(name = "idx_lbl_user_event", columnList = "userId,eventType")
})
public class LearningBehaviorLog {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", length = 64, nullable = false)
    private String userId;

    @Column(name = "event_type", length = 20, nullable = false)
    private String eventType;

    @Column(length = 255)
    private String concept;

    @Column(columnDefinition = "JSON")
    private String detail;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public LearningBehaviorLog() {}

    public LearningBehaviorLog(String userId, String eventType, String concept, String detail) {
        this.userId = userId;
        this.eventType = eventType;
        this.concept = concept;
        this.detail = detail;
    }

    @PrePersist void prePersist() {
        if (this.createdAt == null) this.createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public String getConcept() { return concept; }
    public void setConcept(String concept) { this.concept = concept; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
