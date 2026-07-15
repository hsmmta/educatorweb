package org.example.educatorweb.learninglog.repository;

import org.example.educatorweb.learninglog.model.LearningBehaviorLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface LearningBehaviorLogRepository extends JpaRepository<LearningBehaviorLog, Long> {

    long countByUserIdAndEventTypeAndConcept(String userId, String eventType, String concept);

    long countByUserIdAndEventType(String userId, String eventType);

    List<LearningBehaviorLog> findByUserIdAndEventTypeAndConceptOrderByCreatedAtDesc(
        String userId, String eventType, String concept);
}
