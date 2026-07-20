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
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.Map;

/**
 * SSE endpoint for real-time push notifications.
 * GET /api/push/subscribe?studentId=xxx
 */
@RestController
@RequestMapping("/api/push")
public class PushNotifyController {

    private static final Logger log = LoggerFactory.getLogger(PushNotifyController.class);

    private final PushTriggerService pushTrigger;

    /** Lightweight sink for report-updated notifications. */
    private final Sinks.Many<String> reportUpdateSink =
        Sinks.many().multicast().onBackpressureBuffer();

    public PushNotifyController(PushTriggerService pushTrigger) {
        this.pushTrigger = pushTrigger;
    }

    /** Fire a report-updated notification to a specific user's SSE connection. */
    public void notifyReportUpdated(String userId) {
        reportUpdateSink.tryEmitNext(userId);
    }

    /** Fire a proficiency milestone notification (proficiency >= 60%). */
    public void notifyMilestone(String userId, String concept,
                                int proficiencyPct, String nextNode) {
        try {
            String payload = new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(Map.of(
                    "type", "PROFICIENCY_MILESTONE",
                    "concept", concept,
                    "proficiency", proficiencyPct,
                    "nextNode", nextNode != null ? nextNode : ""
                ));
            reportUpdateSink.tryEmitNext(userId + "::" + payload);
        } catch (Exception e) {
            log.debug("PushNotifyController: milestone notify skipped: {}", e.getMessage());
        }
    }

    @GetMapping(value = "/subscribe", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> subscribe(@RequestParam String studentId) {

        Flux<ServerSentEvent<Object>> pushEvents = pushTrigger.getNotificationSink()
            .asFlux()
            .filter(n -> n.userId().equals(studentId))
            .map(n -> ServerSentEvent.<Object>builder()
                .data(n)
                .build());

        Flux<ServerSentEvent<Object>> reportUpdates = reportUpdateSink.asFlux()
            .filter(msg -> {
                String uid = msg.contains("::") ? msg.substring(0, msg.indexOf("::")) : msg;
                return uid.equals(studentId);
            })
            .map(msg -> {
                Object data;
                if (msg.contains("::")) {
                    String jsonPart = msg.substring(msg.indexOf("::") + 2);
                    try {
                        data = new com.fasterxml.jackson.databind.ObjectMapper()
                            .readValue(jsonPart, Map.class);
                    } catch (Exception e) {
                        data = Map.of("type", "REPORT_UPDATED");
                    }
                } else {
                    data = Map.of("type", "REPORT_UPDATED");
                }
                return ServerSentEvent.<Object>builder().data(data).build();
            });

        Flux<ServerSentEvent<Object>> heartbeat = Flux.interval(Duration.ofSeconds(15))
            .map(tick -> ServerSentEvent.<Object>builder()
                .comment("heartbeat")
                .build());

        return Flux.merge(pushEvents, reportUpdates, heartbeat)
            .doOnSubscribe(s ->
                log.info("PushNotifyController: SSE subscribed for user={}", studentId))
            .doOnCancel(() ->
                log.info("PushNotifyController: SSE cancelled for user={}", studentId));
    }
}
