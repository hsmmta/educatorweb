package org.example.educatorweb.profile.chat;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 画像构建对话消息实体。
 * 记录对话中的每条消息（系统提问 / 学生回答）。
 */
@Entity
@Table(name = "profile_chat_message")
public class ChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", length = 64, nullable = false)
    private String sessionId;

    @Column(name = "sequence", nullable = false)
    private int sequence;

    @Column(name = "role", length = 16, nullable = false)
    private String role; // "SYSTEM" | "STUDENT"

    @Column(name = "content", columnDefinition = "text", nullable = false)
    private String content;

    @Column(name = "dimension_key", length = 32)
    private String dimensionKey; // 当前问题针对的画像维度

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", insertable = false, updatable = false)
    private ChatSession session;

    public ChatMessage() {}

    public ChatMessage(String sessionId, int sequence, String role, String content, String dimensionKey) {
        this.sessionId = sessionId;
        this.sequence = sequence;
        this.role = role;
        this.content = content;
        this.dimensionKey = dimensionKey;
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public int getSequence() { return sequence; }
    public void setSequence(int sequence) { this.sequence = sequence; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getDimensionKey() { return dimensionKey; }
    public void setDimensionKey(String dimensionKey) { this.dimensionKey = dimensionKey; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public ChatSession getSession() { return session; }
    public void setSession(ChatSession session) { this.session = session; }
}