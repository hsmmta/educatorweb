package org.example.educatorweb.profile.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import org.example.educatorweb.profile.converter.JsonListConverter;
import org.example.educatorweb.profile.converter.JsonMapConverter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Entity
@Table(name = "student_profile")
public class StudentProfile {

    @Id
    @Column(name = "student_id", length = 64)
    private String studentId;

    @Column(name = "knowledge_base_level", length = 32, nullable = false)
    private String knowledgeBaseLevel;

    @Column(name = "knowledge_base_confidence", precision = 3, scale = 2, nullable = false)
    private BigDecimal knowledgeBaseConfidence;

    @Column(name = "cognitive_style_type", length = 32, nullable = false)
    private String cognitiveStyleType;

    @Column(name = "cognitive_style_confidence", precision = 3, scale = 2, nullable = false)
    private BigDecimal cognitiveStyleConfidence;

    @Column(name = "error_pattern_tags", columnDefinition = "json", nullable = false)
    @Convert(converter = JsonListConverter.class)
    private List<String> errorPatternTags = new ArrayList<>();

    @Column(name = "error_pattern_confidence", precision = 3, scale = 2, nullable = false)
    private BigDecimal errorPatternConfidence;

    @Column(name = "learning_pace_type", length = 32, nullable = false)
    private String learningPaceType;

    @Column(name = "learning_pace_confidence", precision = 3, scale = 2, nullable = false)
    private BigDecimal learningPaceConfidence;

    @Column(name = "content_preference_type", length = 32, nullable = false)
    private String contentPreferenceType;

    @Column(name = "content_preference_ratio", columnDefinition = "json", nullable = false)
    @Convert(converter = JsonMapConverter.class)
    private Map<String, Double> contentPreferenceRatio;

    @Column(name = "goal_orientation_type", length = 32, nullable = false)
    private String goalOrientationType;

    @Column(name = "goal_orientation_confidence", precision = 3, scale = 2, nullable = false)
    private BigDecimal goalOrientationConfidence;

    @Column(name = "learning_path_json", columnDefinition = "TEXT")
    private String learningPathJson;

    public String getLearningPathJson() { return learningPathJson; }
    public void setLearningPathJson(String learningPathJson) { this.learningPathJson = learningPathJson; }

    /** 累计对话轮数，用于画像更新触发（达到阈值后重置为0） */
    @Column(name = "total_conversation_rounds", nullable = false)
    private int totalConversationRounds = 0;

    /** 上次画像更新时间，用于 3 天间隔触发 */
    @Column(name = "last_profile_update_at")
    private LocalDateTime lastProfileUpdateAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @JsonIgnore
    @OneToMany(mappedBy = "studentProfile", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<StudentKnowledgeProficiency> knowledgeDetails = new ArrayList<>();

    // 无参构造方法（JPA 必需）
    public StudentProfile() {}

    // ========= Getter 和 Setter =========
    public String getStudentId() { return studentId; }
    public void setStudentId(String studentId) { this.studentId = studentId; }

    public String getKnowledgeBaseLevel() { return knowledgeBaseLevel; }
    public void setKnowledgeBaseLevel(String knowledgeBaseLevel) { this.knowledgeBaseLevel = knowledgeBaseLevel; }

    public BigDecimal getKnowledgeBaseConfidence() { return knowledgeBaseConfidence; }
    public void setKnowledgeBaseConfidence(BigDecimal knowledgeBaseConfidence) { this.knowledgeBaseConfidence = knowledgeBaseConfidence; }

    public String getCognitiveStyleType() { return cognitiveStyleType; }
    public void setCognitiveStyleType(String cognitiveStyleType) { this.cognitiveStyleType = cognitiveStyleType; }

    public BigDecimal getCognitiveStyleConfidence() { return cognitiveStyleConfidence; }
    public void setCognitiveStyleConfidence(BigDecimal cognitiveStyleConfidence) { this.cognitiveStyleConfidence = cognitiveStyleConfidence; }

    public List<String> getErrorPatternTags() { return errorPatternTags; }
    public void setErrorPatternTags(List<String> errorPatternTags) { this.errorPatternTags = errorPatternTags; }

    public BigDecimal getErrorPatternConfidence() { return errorPatternConfidence; }
    public void setErrorPatternConfidence(BigDecimal errorPatternConfidence) { this.errorPatternConfidence = errorPatternConfidence; }

    public String getLearningPaceType() { return learningPaceType; }
    public void setLearningPaceType(String learningPaceType) { this.learningPaceType = learningPaceType; }

    public BigDecimal getLearningPaceConfidence() { return learningPaceConfidence; }
    public void setLearningPaceConfidence(BigDecimal learningPaceConfidence) { this.learningPaceConfidence = learningPaceConfidence; }

    public String getContentPreferenceType() { return contentPreferenceType; }
    public void setContentPreferenceType(String contentPreferenceType) { this.contentPreferenceType = contentPreferenceType; }

    public Map<String, Double> getContentPreferenceRatio() { return contentPreferenceRatio; }
    public void setContentPreferenceRatio(Map<String, Double> contentPreferenceRatio) { this.contentPreferenceRatio = contentPreferenceRatio; }

    public String getGoalOrientationType() { return goalOrientationType; }
    public void setGoalOrientationType(String goalOrientationType) { this.goalOrientationType = goalOrientationType; }

    public BigDecimal getGoalOrientationConfidence() { return goalOrientationConfidence; }
    public void setGoalOrientationConfidence(BigDecimal goalOrientationConfidence) { this.goalOrientationConfidence = goalOrientationConfidence; }

    public int getTotalConversationRounds() { return totalConversationRounds; }
    public void setTotalConversationRounds(int totalConversationRounds) { this.totalConversationRounds = totalConversationRounds; }

    public LocalDateTime getLastProfileUpdateAt() { return lastProfileUpdateAt; }
    public void setLastProfileUpdateAt(LocalDateTime lastProfileUpdateAt) { this.lastProfileUpdateAt = lastProfileUpdateAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public List<StudentKnowledgeProficiency> getKnowledgeDetails() { return knowledgeDetails; }
    public void setKnowledgeDetails(List<StudentKnowledgeProficiency> knowledgeDetails) { this.knowledgeDetails = knowledgeDetails; }
}