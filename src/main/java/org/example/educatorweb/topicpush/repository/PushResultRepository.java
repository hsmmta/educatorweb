package org.example.educatorweb.topicpush.repository;

import org.example.educatorweb.topicpush.model.PushResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PushResultRepository extends JpaRepository<PushResult, Long> {

    /** Get push history for a user, newest first. */
    List<PushResult> findByUserIdOrderByCreatedAtDesc(String userId);

    /** Get latest push for a user. */
    PushResult findFirstByUserIdOrderByCreatedAtDesc(String userId);
}
