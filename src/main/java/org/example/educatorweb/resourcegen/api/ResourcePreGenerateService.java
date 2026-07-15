package org.example.educatorweb.resourcegen.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.educatorweb.common.model.GenerateRequest;
import org.example.educatorweb.common.model.ProgressEvent;
import org.example.educatorweb.common.model.ResourceType;
import org.example.educatorweb.resourcegen.infrastructure.FileStorageService;
import org.example.educatorweb.resourcegen.model.PreGeneratedResource;
import org.example.educatorweb.resourcegen.model.PreGeneratedResource.ResourceStatus;
import org.example.educatorweb.resourcegen.repository.PreGeneratedResourceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Triggers async pre-generation of actual resource content via the
 * multi-agent pipeline, then persists results to DB + organized filesystem.
 *
 * <p>Folder layout:
 * <pre>{@code
 *   generated-resources/{userId}/
 *     topic-push/          ← TOPIC_PUSH
 *       {topic}/
 *         DOC/    → {topic}系统讲解.md
 *         QUIZ/   → {topic}巩固练习.json
 *         MINDMAP/→ {topic}思维导图.md
 *     path-push/           ← PATH_PUSH
 *       ...
 * }</pre>
 */
@Service
public class ResourcePreGenerateService {

    private static final Logger log = LoggerFactory.getLogger(ResourcePreGenerateService.class);

    private final ResourceGenerationService generationService;
    private final PreGeneratedResourceRepository repo;
    private final FileStorageService fileStorage;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExecutorService preGenExecutor = Executors.newFixedThreadPool(4,
        r -> { Thread t = new Thread(r, "pregen"); t.setDaemon(true); return t; });

    public ResourcePreGenerateService(ResourceGenerationService generationService,
                                       PreGeneratedResourceRepository repo,
                                       FileStorageService fileStorage) {
        this.generationService = generationService;
        this.repo = repo;
        this.fileStorage = fileStorage;
    }

    /**
     * Synchronously create GENERATING placeholder records for a topic.
     * Returns immediately with saved records (so the caller can embed IDs
     * into PushResult JSON before generation completes).
     */
    public List<PreGeneratedResource> createRecords(String userId, String topic, String pushType) {
        List<ResourceType> types = List.of(ResourceType.DOC, ResourceType.QUIZ, ResourceType.MINDMAP);
        List<PreGeneratedResource> records = new ArrayList<>();
        for (ResourceType rt : types) {
            PreGeneratedResource rec = new PreGeneratedResource(
                null, userId, topic, rt.name(),
                buildTitle(topic, rt), pushType
            );
            rec.setStatus(ResourceStatus.GENERATING);
            records.add(rec);
        }
        records = repo.saveAll(records);
        log.info("PreGenerate: created {} GENERATING records for user={} topic={} pushType={}",
            records.size(), userId, topic, pushType);
        return records;
    }

    /**
     * Async: run the generation pipeline and update the given records with results.
     * Non-blocking — returns a CompletableFuture that completes when all resources are generated.
     */
    public CompletableFuture<List<PreGeneratedResource>> startGeneration(
            List<PreGeneratedResource> records, String userId, String topic, String pushType) {

        List<ResourceType> types = records.stream()
            .map(r -> ResourceType.valueOf(r.getResourceType()))
            .toList();

        GenerateRequest request = new GenerateRequest(userId, topic, types);
        final String pushTypeFolder = pushType.equals("PATH_PUSH") ? "path-push" : "topic-push";

        return CompletableFuture.supplyAsync(() -> {
            try {
                Map<String, Object>[] finalPayload = new Map[1];
                Disposable sub = generationService.generate(request)
                    .doOnNext(event -> {
                        if (event.progressPercent() >= 100 && event.payload() != null) {
                            finalPayload[0] = event.payload();
                        }
                    })
                    .doOnError(err -> log.error("PreGenerate pipeline error for topic={}: {}", topic, err.getMessage()))
                    .subscribe();

                long start = System.currentTimeMillis();
                while (!sub.isDisposed()) {
                    Thread.sleep(200);
                    if (System.currentTimeMillis() - start > 120_000) {
                        sub.dispose();
                        throw new RuntimeException("Generation timed out after 2 min");
                    }
                }

                if (finalPayload[0] != null) {
                    updateRecordsWithResults(records, finalPayload[0], userId, pushTypeFolder, topic);
                } else {
                    markAllFailed(records, "No final payload received from pipeline");
                }
            } catch (Exception e) {
                log.error("PreGenerate failed for topic={}: {}", topic, e.getMessage());
                markAllFailed(records, e.getMessage());
            }
            return repo.findAllById(records.stream().map(PreGeneratedResource::getId).toList());
        }, preGenExecutor);
    }

    /**
     * Convenience: create records + kick off generation in one call.
     * Returns future; records are already persisted with GENERATING status.
     */
    public CompletableFuture<List<PreGeneratedResource>> preGenerateForTopic(
            String userId, String topic, String pushType) {
        List<PreGeneratedResource> records = createRecords(userId, topic, pushType);
        return startGeneration(records, userId, topic, pushType);
    }

    /**
     * Generate resources for a single type only (used for quick targeted generation).
     */
    public CompletableFuture<PreGeneratedResource> preGenerateSingle(
            String userId, String topic, ResourceType type, String pushType) {

        PreGeneratedResource rec = new PreGeneratedResource(
            null, userId, topic, type.name(),
            buildTitle(topic, type), pushType
        );
        rec.setStatus(ResourceStatus.GENERATING);
        final PreGeneratedResource savedRec = repo.save(rec);  // final for lambda

        GenerateRequest request = new GenerateRequest(userId, topic, List.of(type));
        final String pushTypeFolder = pushType.equals("PATH_PUSH") ? "path-push" : "topic-push";

        return CompletableFuture.supplyAsync(() -> {
            try {
                Map<String, Object>[] finalPayload = new Map[1];
                Disposable sub = generationService.generate(request)
                    .doOnNext(event -> {
                        if (event.progressPercent() >= 100 && event.payload() != null) {
                            finalPayload[0] = event.payload();
                        }
                    })
                    .subscribe();

                long start = System.currentTimeMillis();
                while (!sub.isDisposed()) {
                    Thread.sleep(200);
                    if (System.currentTimeMillis() - start > 120_000) {
                        sub.dispose();
                        throw new RuntimeException("Generation timed out after 2 min");
                    }
                }

                if (finalPayload[0] != null) {
                    applyPayloadToRecord(savedRec, finalPayload[0], userId, pushTypeFolder, topic);
                } else {
                    savedRec.setStatus(ResourceStatus.FAILED);
                    savedRec.setErrorMsg("No final payload received");
                    repo.save(savedRec);
                }
            } catch (Exception e) {
                log.error("PreGenerate single failed for topic={} type={}: {}", topic, type, e.getMessage());
                savedRec.setStatus(ResourceStatus.FAILED);
                savedRec.setErrorMsg(e.getMessage());
                repo.save(savedRec);
            }
            return repo.findById(savedRec.getId()).orElse(savedRec);
        }, preGenExecutor);
    }

    // ---- internal ----

    private String buildTitle(String topic, ResourceType type) {
        return switch (type) {
            case DOC -> topic + " 系统讲解";
            case QUIZ -> topic + " 巩固练习";
            case MINDMAP -> topic + " 思维导图";
            case CODE -> topic + " 实战代码";
            case VIDEO -> topic + " 视频讲解";
            case PPT -> topic + " 教学课件";
            case HTML -> topic + " 交互课件";
        };
    }

    private String filenameForType(ResourceType type) {
        return switch (type) {
            case DOC, MINDMAP -> "content.md";
            case QUIZ -> "quiz.json";
            case CODE -> "code.py";
            case HTML -> "index.html";
            case PPT -> "presentation.pptx";
            case VIDEO -> "video.mp4";
        };
    }

    private void updateRecordsWithResults(List<PreGeneratedResource> records,
                                           Map<String, Object> payload,
                                           String userId, String pushTypeFolder,
                                           String topic) {
        for (PreGeneratedResource rec : records) {
            applyPayloadToRecord(rec, payload, userId, pushTypeFolder, topic);
        }
    }

    @SuppressWarnings("unchecked")
    private void applyPayloadToRecord(PreGeneratedResource rec,
                                       Map<String, Object> payload,
                                       String userId, String pushTypeFolder,
                                       String topic) {
        ResourceType type;
        try {
            type = ResourceType.valueOf(rec.getResourceType());
        } catch (IllegalArgumentException e) {
            rec.setStatus(ResourceStatus.FAILED);
            rec.setErrorMsg("Unknown resource type: " + rec.getResourceType());
            repo.save(rec);
            return;
        }

        Object resourceData = payload.get(type.name());
        if (resourceData == null) {
            rec.setStatus(ResourceStatus.FAILED);
            rec.setErrorMsg("No result for type " + type.name() + " in pipeline payload");
            repo.save(rec);
            return;
        }

        Map<String, Object> data = (Map<String, Object>) resourceData;
        String content = (String) data.getOrDefault("content", "");
        rec.setRequestId((String) data.getOrDefault("resourceId", "unknown"));

        if (content == null || content.isBlank() || content.startsWith("Generation failed")) {
            rec.setStatus(ResourceStatus.FAILED);
            rec.setErrorMsg(content != null ? content : "Empty content");
            repo.save(rec);
            return;
        }

        try {
            if (type == ResourceType.PPT || type == ResourceType.VIDEO) {
                // File-based: content is the source file path; copy to organized folder
                String filename = filenameForType(type);
                String newPath = fileStorage.copyToOrganized(
                    content, userId, pushTypeFolder, topic, type.name(), filename);
                rec.setFilePath(newPath);
                rec.setContent(null);
            } else {
                // Text-based: write content to organized folder as file
                String filename = filenameForType(type);
                byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
                String filePath = fileStorage.storeOrganized(
                    userId, pushTypeFolder, topic, type.name(), bytes, filename);
                rec.setContent(content);
                rec.setFilePath(filePath);
            }
            rec.setStatus(ResourceStatus.READY);
            rec.setErrorMsg(null);
            log.info("PreGenerate: resource ready — id={} type={} topic={}", rec.getId(), type, topic);
        } catch (Exception e) {
            rec.setStatus(ResourceStatus.FAILED);
            rec.setErrorMsg("Storage error: " + e.getMessage());
            log.error("PreGenerate: failed to store resource id={}: {}", rec.getId(), e.getMessage());
        }
        repo.save(rec);
    }

    private void markAllFailed(List<PreGeneratedResource> records, String error) {
        for (PreGeneratedResource rec : records) {
            rec.setStatus(ResourceStatus.FAILED);
            rec.setErrorMsg(error);
        }
        repo.saveAll(records);
    }
}
