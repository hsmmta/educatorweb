package org.example.educatorweb.learninglog.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "wrong_answer_book")
public class WrongAnswer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "student_id", length = 64, nullable = false)
    private String studentId;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String question;

    @Column(columnDefinition = "JSON", nullable = false)
    private String options;

    @Column(name = "user_answer", length = 10, nullable = false)
    private String userAnswer;

    @Column(name = "correct_answer", length = 10, nullable = false)
    private String correctAnswer;

    @Column(name = "knowledge_point", length = 256)
    private String knowledgePoint;

    @Column(name = "quiz_title", length = 256)
    private String quizTitle;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public WrongAnswer() {}

    public WrongAnswer(String studentId, String question, String options,
                       String userAnswer, String correctAnswer,
                       String knowledgePoint, String quizTitle) {
        this.studentId = studentId;
        this.question = question;
        this.options = options;
        this.userAnswer = userAnswer;
        this.correctAnswer = correctAnswer;
        this.knowledgePoint = knowledgePoint;
        this.quizTitle = quizTitle;
    }

    @PrePersist
    void prePersist() {
        if (this.createdAt == null) this.createdAt = LocalDateTime.now();
    }

    // getters
    public Long getId() { return id; }
    public String getStudentId() { return studentId; }
    public String getQuestion() { return question; }
    public String getOptions() { return options; }
    public String getUserAnswer() { return userAnswer; }
    public String getCorrectAnswer() { return correctAnswer; }
    public String getKnowledgePoint() { return knowledgePoint; }
    public String getQuizTitle() { return quizTitle; }
    public LocalDateTime getCreatedAt() { return createdAt; }

    // setters
    public void setId(Long id) { this.id = id; }
    public void setStudentId(String studentId) { this.studentId = studentId; }
    public void setQuestion(String question) { this.question = question; }
    public void setOptions(String options) { this.options = options; }
    public void setUserAnswer(String userAnswer) { this.userAnswer = userAnswer; }
    public void setCorrectAnswer(String correctAnswer) { this.correctAnswer = correctAnswer; }
    public void setKnowledgePoint(String knowledgePoint) { this.knowledgePoint = knowledgePoint; }
    public void setQuizTitle(String quizTitle) { this.quizTitle = quizTitle; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
