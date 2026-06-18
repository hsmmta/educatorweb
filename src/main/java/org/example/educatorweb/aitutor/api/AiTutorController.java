package org.example.educatorweb.aitutor.api;

import org.example.educatorweb.aitutor.model.ChatRequest;
import org.example.educatorweb.aitutor.model.ChatResponse;
import org.example.educatorweb.aitutor.service.AiTutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
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
     * AI 助教问答接口。
     *
     * <p>接收学生的提问，检索知识库相关内容，
     * 结合对话历史构建 prompt 后调用大模型生成回答，
     * 并将本轮对话存入 Chroma 向量数据库。
     *
     * @param request 包含 studentId、question、可选的 conversationId
     * @return 包含 answer、参考来源、conversationId 的响应
     */
    @PostMapping(value = "/chat", consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ChatResponse> chat(@RequestBody ChatRequest request) {
        return Mono.fromCallable(() -> {
            log.info("AiTutorController: chat request student={}", request.studentId());
            return tutorService.chat(request);
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
