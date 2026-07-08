package org.example.educatorweb.topicpush.repository;

import org.example.educatorweb.topicpush.model.TopicCache;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TopicCacheRepository extends JpaRepository<TopicCache, Long> {

    /** Find unpushed topics for a user, ordered by ended_at ascending (oldest first). */
    List<TopicCache> findByUserIdAndPushedFalseOrderByEndedAtAsc(String userId);

    /** Count unpushed topics for a user. */
    long countByUserIdAndPushedFalse(String userId);

    /** Mark a batch of topics as pushed. */
    @Modifying
    @Query("UPDATE TopicCache t SET t.pushed = true WHERE t.id IN :ids")
    void markPushed(List<Long> ids);

    /** Find recent topics for a user, ordered by ended_at descending (用于"最近学过"). */
    @Query("SELECT t FROM TopicCache t WHERE t.userId = :userId ORDER BY t.endedAt DESC")
    List<TopicCache> findRecentByUserId(@Param("userId") String userId, Pageable pageable);

    /** Find distinct topic labels for a user matching a query string (用于搜索补全). */
    @Query("SELECT DISTINCT t.topicLabel FROM TopicCache t WHERE t.userId = :userId AND LOWER(t.topicLabel) LIKE LOWER(CONCAT('%', :q, '%'))")
    List<String> findTopicLabelsByUserIdAndQuery(@Param("userId") String userId, @Param("q") String q);
}
