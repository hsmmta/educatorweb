package org.example.educatorweb.profile.passive;

import org.example.educatorweb.aitutor.config.ChromaClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Tracks which conversations have been processed for profile updates
 * using a Redis timestamp cursor. Each student gets an independent cursor.
 *
 * Key pattern: {@code profile:cursor:{studentId}} → ISO-8601 timestamp.
 * Any conversation with a message timestamp > cursor is considered unprocessed.
 */
@Component
public class ProcessedConversationTracker {

    private static final Logger log = LoggerFactory.getLogger(ProcessedConversationTracker.class);
    private static final String KEY_PREFIX = "profile:cursor:";
    private static final String DEFAULT_CURSOR = "1970-01-01T00:00:00.000Z";

    private final RedisTemplate<String, String> redisTemplate;
    private final ChromaClient chromaClient;

    public ProcessedConversationTracker(RedisTemplate<String, String> redisTemplate,
                                         ChromaClient chromaClient) {
        this.redisTemplate = redisTemplate;
        this.chromaClient = chromaClient;
    }

    /**
     * Count distinct unprocessed conversations for a student.
     */
    public int countUnprocessed(String studentId) {
        return getUnprocessedConversationIds(studentId).size();
    }

    /**
     * Get the list of unprocessed conversation IDs.
     */
    public List<String> getUnprocessedConversationIds(String studentId) {
        String cursor = getCursor(studentId);
        return chromaClient.getConversationIdsAfterCursor(studentId, cursor);
    }

    /**
     * Update the cursor to the given timestamp, marking all conversations
     * up to (and including) that timestamp as processed.
     */
    public void markProcessed(String studentId, String maxTimestamp) {
        if (maxTimestamp == null || maxTimestamp.isBlank()) return;
        String key = KEY_PREFIX + studentId;
        redisTemplate.opsForValue().set(key, maxTimestamp);
        log.debug("ProcessedConversationTracker: cursor updated for student={} to {}", studentId, maxTimestamp);
    }

    private String getCursor(String studentId) {
        String key = KEY_PREFIX + studentId;
        String cursor = redisTemplate.opsForValue().get(key);
        return cursor != null ? cursor : DEFAULT_CURSOR;
    }
}
