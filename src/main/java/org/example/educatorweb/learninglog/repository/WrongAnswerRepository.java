package org.example.educatorweb.learninglog.repository;

import org.example.educatorweb.learninglog.model.WrongAnswer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface WrongAnswerRepository extends JpaRepository<WrongAnswer, Long> {

    List<WrongAnswer> findByStudentIdOrderByCreatedAtDesc(String studentId);

    void deleteByStudentId(String studentId);
}
