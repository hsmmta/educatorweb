package org.example.educatorweb.topicpush.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "topic_cache", indexes = {
    @Index(name = "idx_user_pushed", columnList = "userId,pushed"),
    @Index(name = "idx_user_ended", columnList = "userId,endedAt")
})
public class TopicCache {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", length = 64, nullable = false)
    private String userId;

    @Column(name = "topic_label", length = 100, nullable = false)
    private String topicLabel;

    @Column(name = "qa_text", columnDefinition = "TEXT", nullable = false)
    private String qaText;

    @Column(name = "conversation_id", length = 64)
    private String conversationId;

    @Column(name = "ended_at", nullable = false)
    private LocalDateTime endedAt;

    @Column(name = "pushed", nullable = false)
    private Boolean pushed = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public TopicCache() {}

    public TopicCache(String userId, String topicLabel, String qaText,
                      String conversationId, LocalDateTime endedAt) {
        this.userId = userId;
        this.topicLabel = topicLabel;
        this.qaText = qaText;
        this.conversationId = conversationId;
        this.endedAt = endedAt;
    }

    @PrePersist
    void prePersist() {
        if (this.createdAt == null) this.createdAt = LocalDateTime.now();
        if (this.pushed == null) this.pushed = false;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getTopicLabel() { return topicLabel; }
    public void setTopicLabel(String topicLabel) { this.topicLabel = topicLabel; }
    public String getQaText() { return qaText; }
    public void setQaText(String qaText) { this.qaText = qaText; }
    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }
    public LocalDateTime getEndedAt() { return endedAt; }
    public void setEndedAt(LocalDateTime endedAt) { this.endedAt = endedAt; }
    public Boolean getPushed() { return pushed; }
    public void setPushed(Boolean pushed) { this.pushed = pushed; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
