package org.example.educatorweb.resourcegen.api;

import org.example.educatorweb.dto.ResponseResult;
import org.example.educatorweb.learninglog.service.LearningBehaviorService;
import org.example.educatorweb.resourcegen.model.PreGeneratedResource;
import org.example.educatorweb.resourcegen.repository.PreGeneratedResourceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Serve pre-generated resource content to the frontend.
 */
@RestController
@RequestMapping("/api/resources")
public class ResourceViewController {

    private static final Logger log = LoggerFactory.getLogger(ResourceViewController.class);

    private final PreGeneratedResourceRepository repo;
    private final LearningBehaviorService behaviorService;

    public ResourceViewController(PreGeneratedResourceRepository repo,
                                   LearningBehaviorService behaviorService) {
        this.repo = repo;
        this.behaviorService = behaviorService;
    }

    /** GET /api/resources/{id} — get a single resource with full content */
    @GetMapping("/{id}")
    public ResponseResult<Map<String, Object>> getResource(@PathVariable Long id) {
        PreGeneratedResource r = repo.findById(id).orElse(null);
        if (r == null) {
            return ResponseResult.error("Resource not found: " + id);
        }
        // Log resource view (only for READY resources — skips GENERATING/FAILED)
        if (r.getStatus() == PreGeneratedResource.ResourceStatus.READY && r.getTopic() != null) {
            try {
                behaviorService.logResourceView(r.getUserId(), r.getTopic(),
                    r.getResourceType(), r.getId(), r.getTitle());
            } catch (Exception e) {
                log.debug("ResourceView: behavior log failed (non-critical): {}", e.getMessage());
            }
        }
        return ResponseResult.success(toMap(r));
    }

    /** GET /api/resources/user/{userId} — list all resources for a user, newest first */
    @GetMapping("/user/{userId}")
    public ResponseResult<List<Map<String, Object>>> getUserResources(@PathVariable String userId) {
        List<PreGeneratedResource> list =
            repo.findByUserIdAndStatusOrderByCreatedAtDesc(
                userId, PreGeneratedResource.ResourceStatus.READY);
        List<Map<String, Object>> result = list.stream().map(this::toMap).toList();
        return ResponseResult.success(result);
    }

    /** GET /api/resources/status-check/{id} — lightweight status check (no content) */
    @GetMapping("/status-check/{id}")
    public ResponseResult<Map<String, Object>> checkStatus(@PathVariable Long id) {
        PreGeneratedResource r = repo.findById(id).orElse(null);
        if (r == null) {
            return ResponseResult.error("Resource not found: " + id);
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", r.getId());
        map.put("status", r.getStatus().name());
        map.put("title", r.getTitle());
        map.put("resourceType", r.getResourceType());
        map.put("errorMsg", r.getErrorMsg());
        return ResponseResult.success(map);
    }

    private Map<String, Object> toMap(PreGeneratedResource r) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", r.getId());
        map.put("requestId", r.getRequestId());
        map.put("userId", r.getUserId());
        map.put("topic", r.getTopic());
        map.put("resourceType", r.getResourceType());
        map.put("title", r.getTitle());
        map.put("content", r.getContent());
        map.put("filePath", r.getFilePath());
        map.put("metadata", r.getMetadata());
        map.put("pushType", r.getPushType());
        map.put("status", r.getStatus().name());
        map.put("errorMsg", r.getErrorMsg());
        map.put("createdAt", r.getCreatedAt() != null ? r.getCreatedAt().toString() : null);
        return map;
    }
}
