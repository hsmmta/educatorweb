package org.example.educatorweb.topicpush.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.educatorweb.dto.ResponseResult;
import org.example.educatorweb.topicpush.model.PushResult;
import org.example.educatorweb.topicpush.repository.PushResultRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/push")
public class PushResultController {

    private static final Logger log = LoggerFactory.getLogger(PushResultController.class);

    private final PushResultRepository resultRepo;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PushResultController(PushResultRepository resultRepo) {
        this.resultRepo = resultRepo;
    }

    /** GET /api/push/results?studentId=xxx — push history, newest first */
    @GetMapping("/results")
    public ResponseResult<List<Map<String, Object>>> getResults(
            @RequestParam String studentId) {
        List<PushResult> results = resultRepo.findByUserIdOrderByCreatedAtDesc(studentId);
        List<Map<String, Object>> parsed = results.stream().map(r -> {
            try {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("id", r.getId());
                map.put("triggerType", r.getTriggerType());
                map.put("resources", objectMapper.readValue(r.getResources(),
                    new TypeReference<List<Map<String, Object>>>() {}));
                map.put("createdAt", r.getCreatedAt().toString());
                return map;
            } catch (Exception e) {
                log.warn("PushResultController: failed to parse resources for id={}", r.getId());
                return Map.<String, Object>of(
                    "id", r.getId(),
                    "triggerType", r.getTriggerType(),
                    "resources", List.of(),
                    "createdAt", r.getCreatedAt().toString()
                );
            }
        }).toList();
        return ResponseResult.success(parsed);
    }

    /** GET /api/push/latest?studentId=xxx — latest push only */
    @GetMapping("/latest")
    public ResponseResult<Map<String, Object>> getLatest(
            @RequestParam String studentId) {
        PushResult latest = resultRepo.findFirstByUserIdOrderByCreatedAtDesc(studentId);
        if (latest == null) {
            return ResponseResult.success(null);
        }
        try {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", latest.getId());
            map.put("triggerType", latest.getTriggerType());
            map.put("resources", objectMapper.readValue(latest.getResources(),
                new TypeReference<List<Map<String, Object>>>() {}));
            map.put("createdAt", latest.getCreatedAt().toString());
            return ResponseResult.success(map);
        } catch (Exception e) {
            return ResponseResult.success(Map.of(
                "id", latest.getId(),
                "triggerType", latest.getTriggerType(),
                "resources", List.of(),
                "createdAt", latest.getCreatedAt().toString()
            ));
        }
    }
}
