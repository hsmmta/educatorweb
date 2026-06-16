package org.example.educatorweb.knowledgegraph.build.api;

import org.example.educatorweb.knowledgegraph.build.KgBuildAgent;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/kg")
public class KgBuildController {

    private final KgBuildAgent agent;

    public KgBuildController(KgBuildAgent agent) {
        this.agent = agent;
    }

    @PostMapping("/sources/sync")
    public ResponseEntity<Map<String, Object>> syncSources() {
        int chunks = agent.syncSources();
        return ResponseEntity.ok(Map.of("syncedChunks", chunks));
    }

    @PostMapping("/build")
    public ResponseEntity<Map<String, Object>> build(@RequestParam(defaultValue = "incremental") String mode) {
        KgBuildAgent.BuildResult result;
        if ("full".equalsIgnoreCase(mode)) {
            result = agent.buildFull();
        } else {
            result = agent.buildIncremental();
        }
        return ResponseEntity.ok(Map.of(
            "knowledgePoints", result.knowledgePoints(),
            "relationships", result.relationships(),
            "newChunks", result.newChunks()
        ));
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        return ResponseEntity.ok(agent.getStatus());
    }
}
