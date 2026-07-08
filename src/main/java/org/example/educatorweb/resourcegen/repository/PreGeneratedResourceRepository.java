package org.example.educatorweb.resourcegen.repository;

import org.example.educatorweb.resourcegen.model.PreGeneratedResource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PreGeneratedResourceRepository extends JpaRepository<PreGeneratedResource, Long> {

    /** Find all ready resources for a user, newest first. */
    List<PreGeneratedResource> findByUserIdAndStatusOrderByCreatedAtDesc(
        String userId, PreGeneratedResource.ResourceStatus status);

    /** Find resources for a specific topic (any status). */
    List<PreGeneratedResource> findByUserIdAndTopic(String userId, String topic);

    /** Find a resource by requestId. */
    PreGeneratedResource findByRequestId(String requestId);
}
