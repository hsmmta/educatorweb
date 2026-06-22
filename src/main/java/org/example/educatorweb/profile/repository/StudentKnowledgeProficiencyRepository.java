package org.example.educatorweb.profile.repository;

import org.example.educatorweb.profile.model.StudentKnowledgeProficiency;
import org.example.educatorweb.profile.model.StudentKnowledgeProficiencyId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface StudentKnowledgeProficiencyRepository extends JpaRepository<StudentKnowledgeProficiency, StudentKnowledgeProficiencyId> {
    List<StudentKnowledgeProficiency> findByStudentId(String studentId);
    void deleteByStudentId(String studentId);
}