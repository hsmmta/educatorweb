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

@RestController
@RequestMapping("/api")
public class ResourceGenerationController {

    private final ResourceGenerationService service;

    public ResourceGenerationController(ResourceGenerationService service) {
        this.service = service;
    }

    @PostMapping(value = "/generate", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<ProgressEvent>> generate(@RequestBody GenerateRequest req) {
        return service.generate(req)
            .map(event -> ServerSentEvent.<ProgressEvent>builder()
                .id(event.requestId())
                .event(event.stage())
                .data(event)
                .build());
    }
}
