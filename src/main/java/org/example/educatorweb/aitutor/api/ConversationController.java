package org.example.educatorweb.aitutor.api;

import org.example.educatorweb.aitutor.config.ChromaClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

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
     */
    @GetMapping("/conversations")
    public ResponseEntity<List<Map<String, Object>>> listConversations(
            @RequestParam("studentId") String studentId) {
        log.debug("ConversationController: list conversations for student={}", studentId);
        List<Map<String, Object>> conversations = chromaClient.listConversations(studentId);
        return ResponseEntity.ok(conversations);
    }

    /**
     * Get all messages in a conversation, ordered by time.
     */
    @GetMapping("/conversations/{conversationId}/messages")
    public ResponseEntity<List<Map<String, Object>>> getMessages(
            @PathVariable String conversationId,
            @RequestParam("studentId") String studentId) {
        log.debug("ConversationController: messages for conv={} student={}", conversationId, studentId);
        List<Map<String, Object>> messages = chromaClient.getConversationMessages(conversationId, studentId);
        return ResponseEntity.ok(messages);
    }
}
