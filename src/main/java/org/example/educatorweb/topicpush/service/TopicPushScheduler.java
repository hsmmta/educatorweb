package org.example.educatorweb.topicpush.service;

import org.example.educatorweb.topicpush.repository.TopicCacheRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class TopicPushScheduler {

    private static final Logger log = LoggerFactory.getLogger(TopicPushScheduler.class);

    private final TopicCacheRepository cacheRepo;
    private final PushTriggerService pushTrigger;

    public TopicPushScheduler(TopicCacheRepository cacheRepo,
                               PushTriggerService pushTrigger) {
        this.cacheRepo = cacheRepo;
        this.pushTrigger = pushTrigger;
    }

    /**
     * Daily scheduled push at 18:00.
     * Iterates all users with unpushed topics and triggers SCHEDULED push.
     */
    @Scheduled(cron = "${topic.push.scheduler-cron:0 0 18 * * ?}")
    public void scheduledPush() {
        log.info("TopicPushScheduler: daily push started");

        // Find distinct userIds with unpushed topics
        List<String> userIds = cacheRepo.findAll().stream()
            .filter(t -> !t.getPushed())
            .map(t -> t.getUserId())
            .distinct()
            .collect(Collectors.toList());

        if (userIds.isEmpty()) {
            log.info("TopicPushScheduler: no unpushed topics, nothing to do");
        }

        for (String userId : userIds) {
            try {
                pushTrigger.executePush(userId, "SCHEDULED");
            } catch (Exception e) {
                log.error("TopicPushScheduler: push failed for user={}: {}", userId, e.getMessage());
            }
        }

        log.info("TopicPushScheduler: daily push completed, {} users processed", userIds.size());
    }
}
