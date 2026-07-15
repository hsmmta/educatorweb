package org.example.educatorweb.aitutor.api;

import org.example.educatorweb.aitutor.model.ChatRequest;
import org.example.educatorweb.aitutor.model.ChatResponse;
import org.example.educatorweb.aitutor.service.AiTutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.concurrent.Executors;

@RestController
@RequestMapping("/api/tutor")
public class AiTutorController {

    private static final Logger log = LoggerFactory.getLogger(AiTutorController.class);

    private final AiTutorService tutorService;

    public AiTutorController(AiTutorService tutorService) {
        this.tutorService = tutorService;
    }

    /** Blocking endpoint (legacy, kept for compatibility). */
    @PostMapping(value = "/chat", consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ChatResponse> chat(@RequestBody ChatRequest request) {
        return Mono.fromCallable(() -> {
            log.info("AiTutorController: chat request student={}", request.studentId());
            return tutorService.chat(request);
        }).subscribeOn(
            reactor.core.scheduler.Schedulers.fromExecutorService(
                Executors.newCachedThreadPool(r -> {
                    Thread t = new Thread(r, "ai-chat"); t.setDaemon(true); return t;
                })));
    }

    /**
     * Streaming SSE endpoint — emits tokens as they arrive from the LLM.
     * Solves the 30s timeout problem: as long as tokens keep flowing,
     * the connection stays alive (no axios timeout). Also provides
     * a typewriter-style UX.
     */
    @PostMapping(value = "/chat/stream", consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chatStream(@RequestBody ChatRequest request) {
        // No heartbeat here: tokens flow continuously from the LLM, so there's
        // no idle period for proxies to time out. (The push SSE endpoint uses
        // heartbeats because it can be idle for minutes between push events.)
        return tutorService.streamChat(request)
            .map(token -> ServerSentEvent.<String>builder()
                .event("token")
                .data(token)
                .build())
            .concatWith(Flux.just(
                ServerSentEvent.<String>builder()
                    .event("done")
                    .data("{\"conversationId\":\"" + request.conversationId() + "\"}")
                    .build()))
            .doOnSubscribe(s -> log.info("AiTutorController: SSE stream started student={}", request.studentId()))
            .doOnCancel(() -> log.info("AiTutorController: SSE stream cancelled student={}", request.studentId()));
    }
}
