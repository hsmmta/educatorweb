package org.example.educatorweb.resourcegen.api;

import org.example.educatorweb.common.model.GenerateRequest;
import org.example.educatorweb.common.model.ProgressEvent;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.time.Duration;

@RestController
@RequestMapping("/api")
public class ResourceGenerationController {

    /**
     * Comment-only SSE keepalive interval. Progress events are emitted only on
     * stage transitions, and a VIDEO generation stage can stay silent for 2+
     * minutes — longer than proxy idle timeouts (vite dev proxy: 120s), which
     * kill the connection mid-stream (ERR_INCOMPLETE_CHUNKED_ENCODING).
     * Comment frames are ignored by the frontend parsers (they only read
     * "data:" lines) but reset every intermediary's idle timer.
     */
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(15);

    private final ResourceGenerationService service;

    public ResourceGenerationController(ResourceGenerationService service) {
        this.service = service;
    }

    @PostMapping(value = "/generate", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<ProgressEvent>> generate(@RequestBody GenerateRequest req) {
        Flux<ServerSentEvent<ProgressEvent>> events = service.generate(req)
            .map(event -> ServerSentEvent.<ProgressEvent>builder()
                .id(event.requestId())
                .event(event.stage())
                .data(event)
                .build());

        Flux<ServerSentEvent<ProgressEvent>> heartbeat = Flux.interval(HEARTBEAT_INTERVAL)
            .map(i -> ServerSentEvent.<ProgressEvent>builder().comment("keepalive").build());

        // publish(...) multicasts a single upstream subscription: the generation
        // flux is cold, so subscribing it twice (merge branch + completion probe)
        // would otherwise run the whole generation twice.
        return events.publish(shared ->
            shared.mergeWith(heartbeat.takeUntilOther(shared.ignoreElements())));
    }
}
