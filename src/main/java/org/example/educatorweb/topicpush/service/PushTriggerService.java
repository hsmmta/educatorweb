package org.example.educatorweb.topicpush.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.educatorweb.learningpath.ResourceRecommendService;
import org.example.educatorweb.learningpath.model.RecommendedResource;
import org.example.educatorweb.resourcegen.api.ResourcePreGenerateService;
import org.example.educatorweb.resourcegen.model.PreGeneratedResource;
import org.example.educatorweb.topicpush.model.PushResult;
import org.example.educatorweb.topicpush.model.TopicCache;
import org.example.educatorweb.topicpush.repository.PushResultRepository;
import org.example.educatorweb.topicpush.repository.TopicCacheRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Sinks;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class PushTriggerService {

    private static final Logger log = LoggerFactory.getLogger(PushTriggerService.class);

    private final TopicCacheRepository cacheRepo;
    private final PushResultRepository resultRepo;
    private final PushPriorityCalculator calculator;
    private final ResourceRecommendService recommendService;
    private final ResourcePreGenerateService preGenerateService;
    private final ObjectMapper objectMapper;

    /** SSE sink for broadcasting push notifications to connected clients. */
    private final Sinks.Many<PushNotification> notificationSink =
        Sinks.many().multicast().onBackpressureBuffer();

    public PushTriggerService(TopicCacheRepository cacheRepo,
                               PushResultRepository resultRepo,
                               PushPriorityCalculator calculator,
                               ResourceRecommendService recommendService,
                               ResourcePreGenerateService preGenerateService) {
        this.cacheRepo = cacheRepo;
        this.resultRepo = resultRepo;
        this.calculator = calculator;
        this.recommendService = recommendService;
        this.preGenerateService = preGenerateService;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Check and trigger count-based push for a specific user.
     * Called after each topic cache insertion.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void checkAndPush(String userId) {
        long count = cacheRepo.countByUserIdAndPushedFalse(userId);
        if (count >= 3) {
            executePush(userId, "COUNT");
        }
    }

    /**
     * Execute a push for a user (called by count trigger or scheduler).
     * @param triggerType "COUNT" or "SCHEDULED"
     */
    @Transactional
    public void executePush(String userId, String triggerType) {
        log.info("PushTriggerService: executing {} push for user={}", triggerType, userId);

        List<TopicCache> topics = cacheRepo.findByUserIdAndPushedFalseOrderByEndedAtAsc(userId);

        boolean includeWeakness = "SCHEDULED".equals(triggerType);
        List<PushPriorityCalculator.PrioritizedTopic> prioritized =
            calculator.prioritize(topics, userId, includeWeakness);

        // If no topics and no weakness (SCHEDULED with empty cache and no weakness data), skip
        if (prioritized.isEmpty()) {
            log.info("PushTriggerService: no topics to push for user={}, skipping", userId);
            return;
        }

        // Generate resources for each topic
        List<Map<String, Object>> resources = new ArrayList<>();
        for (var pt : prioritized) {
            try {
                // 1. Create pre-generation records (sync, returns immediately with IDs)
                List<PreGeneratedResource> preGenRecords =
                    preGenerateService.createRecords(userId, pt.topicLabel(), "TOPIC_PUSH");

                // Build type → preGeneratedId map
                Map<String, Long> typeToId = new HashMap<>();
                for (PreGeneratedResource pgr : preGenRecords) {
                    typeToId.put(pgr.getResourceType(), pgr.getId());
                }

                // 2. Get resource recommendations
                List<RecommendedResource> recs = recommendService.recommendByTopic(
                    userId, pt.topicLabel(), pt.qaText());

                // 3. Annotate each recommendation with preGeneratedId
                for (RecommendedResource rec : recs) {
                    Long pgrId = typeToId.get(rec.getResourceType());
                    if (pgrId != null) {
                        rec.setPreGeneratedId(pgrId);
                    }
                }

                resources.add(Map.of(
                    "topic", pt.topicLabel(),
                    "isWeakness", pt.isSynthetic(),
                    "resources", recs
                ));

                // 4. Kick off async generation (non-blocking)
                preGenerateService.startGeneration(preGenRecords, userId, pt.topicLabel(), "TOPIC_PUSH");
            } catch (Exception e) {
                log.warn("PushTriggerService: failed to recommend for topic '{}': {}",
                    pt.topicLabel(), e.getMessage());
            }
        }

        // Store push result
        try {
            String json = objectMapper.writeValueAsString(resources);
            PushResult pushResult = new PushResult(userId, triggerType, json);
            resultRepo.save(pushResult);
        } catch (Exception e) {
            log.error("PushTriggerService: failed to serialize push result: {}", e.getMessage());
        }

        // Mark cached topics as pushed
        if (!topics.isEmpty()) {
            List<Long> ids = topics.stream().map(TopicCache::getId).toList();
            cacheRepo.markPushed(ids);
        }

        // Broadcast notification
        PushNotification notification = new PushNotification(
            userId, triggerType, resources.size(), Instant.now()
        );
        Sinks.EmitResult emitResult = notificationSink.tryEmitNext(notification);
        if (emitResult.isFailure()) {
            log.warn("PushTriggerService: SSE emit failed: {}", emitResult);
        }

        log.info("PushTriggerService: push complete for user={}, {} resources pushed",
            userId, resources.size());
    }

    /** Get the SSE sink for controllers to subscribe to. */
    public Sinks.Many<PushNotification> getNotificationSink() {
        return notificationSink;
    }

    /** Push notification DTO for SSE broadcast. */
    public record PushNotification(
        String userId,
        String triggerType,
        int resourceCount,
        Instant timestamp
    ) {}
}
