package org.example.educatorweb.aitutor.api;

import org.example.educatorweb.aitutor.config.ChromaClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/tutor")
public class ConversationController {

    private static final Logger log = LoggerFactory.getLogger(ConversationController.class);

    private final ChromaClient chromaClient;

    public ConversationController(ChromaClient chromaClient) {
        this.chromaClient = chromaClient;
    }

    /**
     * List all conversations for a user, grouped by conversationId.
     * Returns title (first question, truncated) and last message timestamp.
     *
     * <p>The blocking Chroma call is offloaded to a bounded-elastic scheduler
     * so it never runs on the Netty event loop.
     */
    @GetMapping("/conversations")
    public Mono<ResponseEntity<List<Map<String, Object>>>> listConversations(
            @RequestParam("studentId") String studentId) {
        return Mono.fromCallable(() -> {
            log.debug("ConversationController: list conversations for student={}", studentId);
            List<Map<String, Object>> conversations = chromaClient.listConversations(studentId);
            return ResponseEntity.ok(conversations);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Get all messages in a conversation, ordered by time.
     */
    @GetMapping("/conversations/{conversationId}/messages")
    public Mono<ResponseEntity<List<Map<String, Object>>>> getMessages(
            @PathVariable String conversationId,
            @RequestParam("studentId") String studentId) {
        return Mono.fromCallable(() -> {
            log.debug("ConversationController: messages for conv={} student={}", conversationId, studentId);
            List<Map<String, Object>> messages = chromaClient.getConversationMessages(conversationId, studentId);
            return ResponseEntity.ok(messages);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Delete a conversation and all its messages.
     */
    @DeleteMapping("/conversations/{conversationId}")
    public Mono<ResponseEntity<Map<String, Object>>> deleteConversation(
            @PathVariable String conversationId,
            @RequestParam("studentId") String studentId) {
        return Mono.fromCallable(() -> {
            log.info("ConversationController: delete conv={} student={}", conversationId, studentId);
            boolean ok = chromaClient.deleteByConversationId(conversationId, studentId);
            return ResponseEntity.ok(Map.of("deleted", (Object) ok));
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
