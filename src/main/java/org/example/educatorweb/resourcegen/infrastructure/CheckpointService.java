package org.example.educatorweb.resourcegen.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.educatorweb.resourcegen.model.GenerationState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

/**
 * Redis-backed checkpoint service for persisting and resuming generation pipeline state.
 * Checkpoints are stored with key {@code checkpoint:{requestId}} and an expiry of 1 hour.
 * If Redis is unavailable, this bean will not be created and the orchestrator skips checkpointing.
 */
@Service
public class CheckpointService {

    private static final Logger log = LoggerFactory.getLogger(CheckpointService.class);
    private static final String KEY_PREFIX = "checkpoint:";
    private static final Duration TTL = Duration.ofHours(1);

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    public CheckpointService(RedisTemplate<String, String> redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Serialize the given state to JSON and store it under {@code checkpoint:{requestId}}.
     * The entry expires after 1 hour.
     *
     * @param requestId the unique request identifier
     * @param state     the current generation pipeline state
     */
    public void save(String requestId, GenerationState state) {
        try {
            String json = objectMapper.writeValueAsString(state);
            String key = KEY_PREFIX + requestId;
            redisTemplate.opsForValue().set(key, json, TTL);
            log.debug("Saved checkpoint for requestId={}, stage={}", requestId, state.stage());
        } catch (Exception e) {
            log.warn("Failed to save checkpoint for requestId={}: {}", requestId, e.getMessage());
        }
    }

    /**
     * Load and deserialize a previously saved checkpoint.
     *
     * @param requestId the unique request identifier
     * @return the deserialized state, or {@link Optional#empty()} if not found or on deserialization failure
     */
    public Optional<GenerationState> load(String requestId) {
        try {
            String key = KEY_PREFIX + requestId;
            String json = redisTemplate.opsForValue().get(key);
            if (json == null) {
                return Optional.empty();
            }
            GenerationState state = objectMapper.readValue(json, GenerationState.class);
            log.debug("Loaded checkpoint for requestId={}, stage={}", requestId, state.stage());
            return Optional.of(state);
        } catch (Exception e) {
            log.warn("Failed to load checkpoint for requestId={}: {}", requestId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Delete the checkpoint entry for the given request, e.g. after completion.
     *
     * @param requestId the unique request identifier
     */
    public void delete(String requestId) {
        try {
            String key = KEY_PREFIX + requestId;
            redisTemplate.delete(key);
            log.debug("Deleted checkpoint for requestId={}", requestId);
        } catch (Exception e) {
            log.warn("Failed to delete checkpoint for requestId={}: {}", requestId, e.getMessage());
        }
    }
}
