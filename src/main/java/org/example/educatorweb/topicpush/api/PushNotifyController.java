package org.example.educatorweb.topicpush.api;

import org.example.educatorweb.topicpush.service.PushTriggerService;
import org.example.educatorweb.topicpush.service.PushTriggerService.PushNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.time.Duration;

/**
 * SSE endpoint for real-time push notifications.
 * GET /api/push/subscribe?studentId=xxx
 */
@RestController
@RequestMapping("/api/push")
public class PushNotifyController {

    private static final Logger log = LoggerFactory.getLogger(PushNotifyController.class);

    private final PushTriggerService pushTrigger;

    public PushNotifyController(PushTriggerService pushTrigger) {
        this.pushTrigger = pushTrigger;
    }

    @GetMapping(value = "/subscribe", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<PushNotification>> subscribe(@RequestParam String studentId) {

        Flux<PushNotification> events = pushTrigger.getNotificationSink()
            .asFlux()
            .filter(n -> n.userId().equals(studentId));

        // Heartbeat every 15s: keeps the SSE connection alive across proxies (Vite)
        // and prevents EventSource from treating the connection as stale.
        Flux<ServerSentEvent<PushNotification>> heartbeat = Flux.interval(Duration.ofSeconds(15))
            .map(tick -> ServerSentEvent.<PushNotification>builder()
                .comment("heartbeat")
                .build());

        return Flux.merge(
                events.map(e -> ServerSentEvent.<PushNotification>builder()
                    .data(e)
                    .build()),
                heartbeat
            )
            .doOnSubscribe(s ->
                log.info("PushNotifyController: SSE subscribed for user={}", studentId))
            .doOnCancel(() ->
                log.info("PushNotifyController: SSE cancelled for user={}", studentId));
    }
}
