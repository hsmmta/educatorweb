package org.example.educatorweb.profile.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.List;
import java.util.Map;

/**
 * Student learning profile with 6 dimensions, persisted via JPA.
 * Inner dimension types remain as records (serialized to JSON columns).
 * Accessor names match the old record style: knowledgeBase(), cognitiveStyle(), etc.
 */
@Entity
@Table(name = "student_profiles")
public class StudentProfile {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Id
    @Column(name = "student_id")
    private String studentId;

    @Column(name = "knowledge_base", columnDefinition = "TEXT")
    private String knowledgeBaseJson;
    @Column(name = "cognitive_style", columnDefinition = "TEXT")
    private String cognitiveStyleJson;
    @Column(name = "error_pattern", columnDefinition = "TEXT")
    private String errorPatternJson;
    @Column(name = "learning_pace", columnDefinition = "TEXT")
    private String learningPaceJson;
    @Column(name = "content_preference", columnDefinition = "TEXT")
    private String contentPreferenceJson;
    @Column(name = "goal_orientation", columnDefinition = "TEXT")
    private String goalOrientationJson;

    // ---- Dimension records (kept as records, not persisted directly) ----

    public record D1_KnowledgeBase(String level, double confidence, Map<String, String> details) {}
    public record D2_CognitiveStyle(String type, double confidence) {}
    public record D3_ErrorPattern(List<String> tags, double confidence) {}
    public record D4_LearningPace(String type, double confidence) {}
    public record D5_ContentPreference(String type, Map<String, Double> ratio) {}
    public record D6_GoalOrientation(String type, double confidence) {}

    // ---- JPA-required no-arg constructor ----

    public StudentProfile() {}

    // ---- Constructors ----

    /** 6-arg constructor — backward compatible with MockProfileService and existing code. */
    public StudentProfile(D1_KnowledgeBase knowledgeBase, D2_CognitiveStyle cognitiveStyle,
                          D3_ErrorPattern errorPattern, D4_LearningPace learningPace,
                          D5_ContentPreference contentPreference, D6_GoalOrientation goalOrientation) {
        setKnowledgeBase(knowledgeBase);
        setCognitiveStyle(cognitiveStyle);
        setErrorPattern(errorPattern);
        setLearningPace(learningPace);
        setContentPreference(contentPreference);
        setGoalOrientation(goalOrientation);
    }

    /** Full constructor with studentId for JPA repository use. */
    public StudentProfile(String studentId, D1_KnowledgeBase knowledgeBase,
                          D2_CognitiveStyle cognitiveStyle, D3_ErrorPattern errorPattern,
                          D4_LearningPace learningPace, D5_ContentPreference contentPreference,
                          D6_GoalOrientation goalOrientation) {
        this.studentId = studentId;
        setKnowledgeBase(knowledgeBase);
        setCognitiveStyle(cognitiveStyle);
        setErrorPattern(errorPattern);
        setLearningPace(learningPace);
        setContentPreference(contentPreference);
        setGoalOrientation(goalOrientation);
    }

    // ---- Accessors (record-style naming — NOT getXxx) ----

    public String getStudentId() { return studentId; }
    public void setStudentId(String studentId) { this.studentId = studentId; }

    public D1_KnowledgeBase knowledgeBase() { return fromJson(knowledgeBaseJson, D1_KnowledgeBase.class); }
    public void setKnowledgeBase(D1_KnowledgeBase kb) { this.knowledgeBaseJson = toJson(kb); }

    public D2_CognitiveStyle cognitiveStyle() { return fromJson(cognitiveStyleJson, D2_CognitiveStyle.class); }
    public void setCognitiveStyle(D2_CognitiveStyle cs) { this.cognitiveStyleJson = toJson(cs); }

    public D3_ErrorPattern errorPattern() { return fromJson(errorPatternJson, D3_ErrorPattern.class); }
    public void setErrorPattern(D3_ErrorPattern ep) { this.errorPatternJson = toJson(ep); }

    public D4_LearningPace learningPace() { return fromJson(learningPaceJson, D4_LearningPace.class); }
    public void setLearningPace(D4_LearningPace lp) { this.learningPaceJson = toJson(lp); }

    public D5_ContentPreference contentPreference() { return fromJson(contentPreferenceJson, D5_ContentPreference.class); }
    public void setContentPreference(D5_ContentPreference cp) { this.contentPreferenceJson = toJson(cp); }

    public D6_GoalOrientation goalOrientation() { return fromJson(goalOrientationJson, D6_GoalOrientation.class); }
    public void setGoalOrientation(D6_GoalOrientation go) { this.goalOrientationJson = toJson(go); }

    // ---- JSON helpers ----

    private static <T> T fromJson(String json, Class<T> type) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to deserialize " + type.getSimpleName(), e);
        }
    }

    private static String toJson(Object obj) {
        if (obj == null) return null;
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize " + obj.getClass().getSimpleName(), e);
        }
    }
}
