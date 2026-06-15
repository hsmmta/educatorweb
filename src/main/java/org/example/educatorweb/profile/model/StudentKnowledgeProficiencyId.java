package org.example.educatorweb.profile.model;

import java.io.Serializable;
import java.util.Objects;

public class StudentKnowledgeProficiencyId implements Serializable {

    private String studentId;
    private String concept;

    public StudentKnowledgeProficiencyId() {}

    public StudentKnowledgeProficiencyId(String studentId, String concept) {
        this.studentId = studentId;
        this.concept = concept;
    }

    public String getStudentId() { return studentId; }
    public void setStudentId(String studentId) { this.studentId = studentId; }
    public String getConcept() { return concept; }
    public void setConcept(String concept) { this.concept = concept; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof StudentKnowledgeProficiencyId that)) return false;
        return Objects.equals(studentId, that.studentId) && Objects.equals(concept, that.concept);
    }

    @Override
    public int hashCode() {
        return Objects.hash(studentId, concept);
    }
}
