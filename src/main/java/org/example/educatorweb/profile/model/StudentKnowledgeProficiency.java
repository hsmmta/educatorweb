package org.example.educatorweb.profile.model;

import jakarta.persistence.*;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "student_knowledge_proficiency")
@IdClass(StudentKnowledgeProficiencyId.class)
public class StudentKnowledgeProficiency {

    @Id
    @Column(name = "student_id", length = 64)
    private String studentId;

    @Id
    @Column(name = "concept", length = 255)
    private String concept;

    @Column(name = "total_questions")
    private int totalQuestions;

    @Column(name = "correct_questions")
    private int correctQuestions;

    @Column(name = "last_study_time")
    private LocalDateTime lastStudyTime;

    @Column(name = "proficiency", precision = 5, scale = 4)
    private BigDecimal proficiency;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "student_id", insertable = false, updatable = false)
    private StudentProfile studentProfile;

    // 无参构造方法（JPA 必需）
    public StudentKnowledgeProficiency() {}

    public String getStudentId() { return studentId; }
    public void setStudentId(String studentId) { this.studentId = studentId; }

    public String getConcept() { return concept; }
    public void setConcept(String concept) { this.concept = concept; }

    public int getTotalQuestions() { return totalQuestions; }
    public void setTotalQuestions(int totalQuestions) { this.totalQuestions = totalQuestions; }

    public int getCorrectQuestions() { return correctQuestions; }
    public void setCorrectQuestions(int correctQuestions) { this.correctQuestions = correctQuestions; }

    public LocalDateTime getLastStudyTime() { return lastStudyTime; }
    public void setLastStudyTime(LocalDateTime lastStudyTime) { this.lastStudyTime = lastStudyTime; }

    public BigDecimal getProficiency() { return proficiency; }
    public void setProficiency(BigDecimal proficiency) { this.proficiency = proficiency; }

    public StudentProfile getStudentProfile() { return studentProfile; }
    public void setStudentProfile(StudentProfile studentProfile) { this.studentProfile = studentProfile; }
}