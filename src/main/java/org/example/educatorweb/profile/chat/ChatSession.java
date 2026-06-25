package org.example.educatorweb.profile.chat;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 画像构建对话会话实体。
 * 记录一次完整的对话式画像构建/更新会话。
 */
@Entity
@Table(name = "profile_chat_session")
public class ChatSession {

    @Id
    @Column(name = "session_id", length = 64)
    private String sessionId;

    @Column(name = "student_id", length = 64, nullable = false)
    private String studentId;

    @Column(name = "session_type", length = 32, nullable = false)
    private String sessionType; // "INITIAL_BUILD" | "REFRESH" | "QUICK_CHECK"

    @Column(name = "completed_dimensions", length = 512)
    private String completedDimensions; // comma-separated dimension keys

    @Column(name = "profile_extracted", nullable = false)
    private boolean profileExtracted;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "session", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("sequence ASC")
    private List<ChatMessage> messages = new ArrayList<>();

    public ChatSession() {}

    public ChatSession(String sessionId, String studentId, String sessionType) {
        this.sessionId = sessionId;
        this.studentId = studentId;
        this.sessionType = sessionType;
        this.completedDimensions = "";
        this.profileExtracted = false;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getStudentId() { return studentId; }
    public void setStudentId(String studentId) { this.studentId = studentId; }
    public String getSessionType() { return sessionType; }
    public void setSessionType(String sessionType) { this.sessionType = sessionType; }
    public String getCompletedDimensions() { return completedDimensions; }
    public void setCompletedDimensions(String completedDimensions) { this.completedDimensions = completedDimensions; }
    public boolean isProfileExtracted() { return profileExtracted; }
    public void setProfileExtracted(boolean profileExtracted) { this.profileExtracted = profileExtracted; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public List<ChatMessage> getMessages() { return messages; }
    public void setMessages(List<ChatMessage> messages) { this.messages = messages; }
}