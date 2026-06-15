package org.example.educatorweb.profile.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Plain data object representing a student's proficiency on a knowledge point.
 * (Was a JPA @Entity; converted to POJO after StudentProfile became a record.)
 */
public class StudentKnowledgeProficiency {
    private Long id;
    private String concept;
    private Integer totalQuestions;
    private Integer correctQuestions;
    private LocalDateTime lastStudyTime;
    private BigDecimal proficiency;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getConcept() { return concept; }
    public void setConcept(String concept) { this.concept = concept; }
    public Integer getTotalQuestions() { return totalQuestions; }
    public void setTotalQuestions(Integer totalQuestions) { this.totalQuestions = totalQuestions; }
    public Integer getCorrectQuestions() { return correctQuestions; }
    public void setCorrectQuestions(Integer correctQuestions) { this.correctQuestions = correctQuestions; }
    public LocalDateTime getLastStudyTime() { return lastStudyTime; }
    public void setLastStudyTime(LocalDateTime lastStudyTime) { this.lastStudyTime = lastStudyTime; }
    public BigDecimal getProficiency() { return proficiency; }
    public void setProficiency(BigDecimal proficiency) { this.proficiency = proficiency; }
}
