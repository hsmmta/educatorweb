package org.example.educatorweb.learningpath;

import org.example.educatorweb.topicpush.service.PushTriggerService;
import org.example.educatorweb.topicpush.service.PushTriggerService.PushNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Bridges path recalculation events to the existing SSE notification sink.
 * Lives in learningpath to avoid circular dependency (PushTriggerService
 * depends on ResourceRecommendService → LearningPathService).
 */
@Component
public class PathNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(PathNotificationListener.class);
    private final PushTriggerService pushTrigger;

    public PathNotificationListener(PushTriggerService pushTrigger) {
        this.pushTrigger = pushTrigger;
    }

    @Async
    @EventListener
    public void onPathRecalculated(PathRecalculatedEvent event) {
        PushNotification notif = new PushNotification(
            event.studentId(), "PATH_UPDATED", event.remainingNodes(), Instant.now());
        pushTrigger.getNotificationSink().tryEmitNext(notif);
        log.info("PathNotificationListener: SSE notification sent for user={}, remaining nodes={}",
            event.studentId(), event.remainingNodes());
    }
}
