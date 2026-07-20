package org.example.educatorweb.profile.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "proficiency_snapshot", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"student_id", "concept", "snapshot_date"})
})
public class ProficiencySnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "student_id", length = 64, nullable = false)
    private String studentId;

    @Column(length = 255, nullable = false)
    private String concept;

    @Column(precision = 5, scale = 4, nullable = false)
    private BigDecimal proficiency;

    @Column(name = "effective_proficiency", precision = 5, scale = 4, nullable = false)
    private BigDecimal effectiveProficiency;

    @Column(name = "snapshot_date", nullable = false)
    private LocalDate snapshotDate;

    public ProficiencySnapshot() {}

    public ProficiencySnapshot(String studentId, String concept,
                               BigDecimal proficiency, BigDecimal effectiveProficiency,
                               LocalDate snapshotDate) {
        this.studentId = studentId;
        this.concept = concept;
        this.proficiency = proficiency;
        this.effectiveProficiency = effectiveProficiency;
        this.snapshotDate = snapshotDate;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getStudentId() { return studentId; }
    public void setStudentId(String studentId) { this.studentId = studentId; }
    public String getConcept() { return concept; }
    public void setConcept(String concept) { this.concept = concept; }
    public BigDecimal getProficiency() { return proficiency; }
    public void setProficiency(BigDecimal proficiency) { this.proficiency = proficiency; }
    public BigDecimal getEffectiveProficiency() { return effectiveProficiency; }
    public void setEffectiveProficiency(BigDecimal effectiveProficiency) { this.effectiveProficiency = effectiveProficiency; }
    public LocalDate getSnapshotDate() { return snapshotDate; }
    public void setSnapshotDate(LocalDate snapshotDate) { this.snapshotDate = snapshotDate; }
}
