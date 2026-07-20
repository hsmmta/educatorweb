package org.example.educatorweb.learninglog.repository;

import org.example.educatorweb.learninglog.model.LearningBehaviorLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface LearningBehaviorLogRepository extends JpaRepository<LearningBehaviorLog, Long> {

    long countByUserIdAndEventTypeAndConcept(String userId, String eventType, String concept);

    long countByUserIdAndEventType(String userId, String eventType);

    List<LearningBehaviorLog> findByUserIdAndEventTypeAndConceptOrderByCreatedAtDesc(
        String userId, String eventType, String concept);

    /** Count distinct days with any logged activity for a user. */
    @Query("SELECT COUNT(DISTINCT FUNCTION('DATE', l.createdAt)) FROM LearningBehaviorLog l WHERE l.userId = :userId")
    long countActiveDaysByUserId(@Param("userId") String userId);

    /** Get all events for a user within a date range. */
    @Query("SELECT l FROM LearningBehaviorLog l WHERE l.userId = :userId AND l.createdAt >= :start AND l.createdAt <= :end ORDER BY l.createdAt")
    List<LearningBehaviorLog> findByUserIdAndCreatedAtBetween(
        @Param("userId") String userId,
        @Param("start") LocalDateTime start,
        @Param("end") LocalDateTime end);
}
