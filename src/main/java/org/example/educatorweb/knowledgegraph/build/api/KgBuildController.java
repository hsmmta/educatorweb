package org.example.educatorweb.knowledgegraph.build.api;

import org.example.educatorweb.knowledgegraph.build.KaggleImporter;
import org.example.educatorweb.knowledgegraph.build.KgBuildAgent;
import org.example.educatorweb.knowledgegraph.build.LlmPrerequisiteGenerator;
import org.example.educatorweb.knowledgegraph.build.MOOCCubeImporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/kg")
public class KgBuildController {

    private static final Logger log = LoggerFactory.getLogger(KgBuildController.class);

    private final KgBuildAgent agent;
    private final KaggleImporter kaggleImporter;
    private final MOOCCubeImporter moocImporter;
    private final LlmPrerequisiteGenerator llmPrereqGen;

    public KgBuildController(KgBuildAgent agent, KaggleImporter kaggleImporter,
                              MOOCCubeImporter moocImporter,
                              LlmPrerequisiteGenerator llmPrereqGen) {
        this.agent = agent;
        this.kaggleImporter = kaggleImporter;
        this.moocImporter = moocImporter;
        this.llmPrereqGen = llmPrereqGen;
    }

    @PostMapping("/sources/sync")
    public ResponseEntity<Map<String, Object>> syncSources() {
        int chunks = agent.syncSources();
        return ResponseEntity.ok(Map.of("syncedChunks", chunks));
    }

    @PostMapping("/build")
    public ResponseEntity<Map<String, Object>> build(@RequestParam(defaultValue = "incremental") String mode,
                                                      @RequestParam(defaultValue = "false") boolean confirm) {
        KgBuildAgent.BuildResult result;
        if ("full".equalsIgnoreCase(mode)) {
            if (!confirm) {
                return ResponseEntity.badRequest().body(Map.of(
                    "error", "mode=full will DELETE all knowledge graph data. Add ?confirm=true to proceed.",
                    "currentNodeCount", agent.getStatus().get("knowledgePointCount")
                ));
            }
            log.warn("KgBuildController: FULL build requested with confirmation — clearing all KG data");
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

    /**
     * Import knowledge points from an external dataset (Kaggle ML Course KG, etc.).
     * Expects node.csv and edge.csv under the given directory.
     */
    @PostMapping("/import-kaggle")
    public ResponseEntity<Map<String, Object>> importKaggle(@RequestParam String dir) {
        try {
            KaggleImporter.ImportResult result = kaggleImporter.importFromDir(dir);
            return ResponseEntity.ok(Map.of(
                "nodeCount", result.nodeCount(),
                "edgeCount", result.edgeCount(),
                "message", result.message()
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** Import concepts + courses from MOOCCube dataset directory. */
    @PostMapping("/import-mooccube")
    public ResponseEntity<Map<String, Object>> importMOOCCube(@RequestParam String dir) {
        try {
            MOOCCubeImporter.ImportResult result = moocImporter.importFromDir(dir);
            return ResponseEntity.ok(Map.of(
                "nodeCount", result.nodeCount(),
                "edgeCount", result.edgeCount(),
                "message", result.message()
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Use LLM (DeepSeek) to identify missing prerequisite relationships.
     * Processes concepts that have 0 REQUIRES edges, batch by batch.
     *
     * @param limit max concepts to process (default 0 = unlimited, ~100 recommended per run)
     */
    @PostMapping("/generate-prerequisites")
    public ResponseEntity<Map<String, Object>> generatePrerequisites(
            @RequestParam(defaultValue = "0") int limit) {
        try {
            LlmPrerequisiteGenerator.GenResult result = llmPrereqGen.generate(limit);
            return ResponseEntity.ok(Map.of(
                "conceptsChecked", result.conceptsChecked(),
                "prereqsFound", result.prereqsFound(),
                "message", result.message()
            ));
        } catch (Exception e) {
            log.error("KgBuildController: LLM prerequisite generation failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** After import, link existing nodes (CONTAINS + HAS_RESOURCE) without clearing. */
    @PostMapping("/link-after-import")
    public ResponseEntity<Map<String, Object>> linkAfterImport() {
        KgBuildAgent.BuildResult result = agent.linkExistingNodes();
        return ResponseEntity.ok(Map.of(
            "knowledgePoints", result.knowledgePoints(),
            "relationships", result.relationships(),
            "newChunks", result.newChunks()
        ));
    }
}
