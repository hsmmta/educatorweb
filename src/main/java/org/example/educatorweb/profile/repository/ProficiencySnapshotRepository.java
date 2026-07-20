package org.example.educatorweb.profile.repository;

import org.example.educatorweb.profile.model.ProficiencySnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface ProficiencySnapshotRepository extends JpaRepository<ProficiencySnapshot, Long> {

    Optional<ProficiencySnapshot> findByStudentIdAndConceptAndSnapshotDate(
        String studentId, String concept, LocalDate snapshotDate);

    List<ProficiencySnapshot> findByStudentIdAndSnapshotDateBetween(
        String studentId, LocalDate start, LocalDate end);

    List<ProficiencySnapshot> findByStudentId(String studentId);
}
