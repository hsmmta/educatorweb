package org.example.educatorweb.aitutor.api;

import org.example.educatorweb.aitutor.model.ChatRequest;
import org.example.educatorweb.aitutor.model.ChatResponse;
import org.example.educatorweb.aitutor.service.AiTutorService;
import org.example.educatorweb.aitutor.service.StreamEvent;
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
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/tutor")
public class AiTutorController {

    private static final Logger log = LoggerFactory.getLogger(AiTutorController.class);

    private final AiTutorService tutorService;

    public AiTutorController(AiTutorService tutorService) {
        this.tutorService = tutorService;
    }

    /**
     * AI 助教问答接口（阻塞模式，兼容旧版）。
     */
    @PostMapping(value = "/chat", consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ChatResponse> chat(@RequestBody ChatRequest request) {
        return Mono.fromCallable(() -> {
            log.info("AiTutorController: chat request student={}", request.studentId());
            return tutorService.chat(request);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * AI 助教流式问答接口。
     * SSE 事件流：status → token token ... → done
     */
    @PostMapping(value = "/chat/stream", consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<StreamEvent>> chatStream(@RequestBody ChatRequest request) {
        log.info("AiTutorController: stream request student={}", request.studentId());
        return tutorService.chatStream(request)
            .map(event -> ServerSentEvent.<StreamEvent>builder()
                .event(event.type())
                .data(event)
                .build());
    }
}
