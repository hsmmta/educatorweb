package org.example.educatorweb.resourcegen.agents;

import org.example.educatorweb.common.model.GenerateRequest;
import org.example.educatorweb.common.model.ResourceType;
import org.example.educatorweb.resourcegen.config.ModelRegistry;
import org.example.educatorweb.resourcegen.config.ReviewKeywordsConfig;
import org.example.educatorweb.resourcegen.infrastructure.ModelProvider;
import org.example.educatorweb.resourcegen.model.GeneratedResource;
import org.example.educatorweb.resourcegen.model.GenerationState;
import org.example.educatorweb.resourcegen.model.ProgressStage;
import org.example.educatorweb.resourcegen.model.QualityReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReviewAgentTest {

    @Mock
    private ModelProvider provider;

    private ReviewKeywordsConfig reviewKeywordsConfig;

    private ReviewAgent reviewAgent;

    @BeforeEach
    void setUp() {
        reviewKeywordsConfig = new ReviewKeywordsConfig();
        // Use a real ModelRegistry backed by a mock ModelProvider
        // (avoid Mockito inline-mock issue with concrete class on JDK 25)
        var registry = new ModelRegistry(provider, provider);
        reviewAgent = new ReviewAgent(registry, reviewKeywordsConfig);
    }

    // ---- Test 1: L1 keyword block — content with "violence" → passed=false ----

    @Test
    void shouldBlockContentWithViolenceKeyword() {
        // L1: configure blocked keywords on the real config instance
        reviewKeywordsConfig.setKeywords(List.of("violence", "hate speech"));

        // L2: LLM review passes (so only L1 matters)
        when(provider.chat(anyString())).thenReturn("passed");

        // Build state with content containing the blocked keyword
        GenerateRequest req = new GenerateRequest("student-1", "SVM", List.of(ResourceType.DOC));
        GenerationState state = GenerationState.initial(req);

        GeneratedResource doc = GeneratedResource.of(ResourceType.DOC, "SVM", "SVM Tutorial",
            "This document contains violence related content that should be blocked.");
        state = state.withResult(ResourceType.DOC, doc);

        // Execute
        GenerationState result = reviewAgent.execute(state);

        // Verify
        assertThat(result.reviews()).isNotEmpty();
        QualityReport report = result.reviews().get(result.reviews().size() - 1);
        assertThat(report.passed()).isFalse();
        assertThat(report.issues()).anyMatch(i -> i.layer() == QualityReport.QualityLayer.L1_KEYWORD
            && i.description().contains("violence"));
        assertThat(result.reviewRetries()).isEqualTo(1);
        assertThat(result.stage()).isEqualTo(ProgressStage.DESIGN); // first retry, <= 3
    }

    // ---- Test 2: Retry routing — first fail → stage=DESIGN ----

    @Test
    void shouldRouteToDesignOnFirstFailure() {
        // L1: blocked keyword
        reviewKeywordsConfig.setKeywords(List.of("blocked"));

        // L2: LLM review passes
        when(provider.chat(anyString())).thenReturn("passed");

        // Build state with reviewRetries = 0 (first attempt)
        GenerateRequest req = new GenerateRequest("student-1", "Math", List.of(ResourceType.DOC));
        GenerationState state = GenerationState.initial(req);

        GeneratedResource doc = GeneratedResource.of(ResourceType.DOC, "Math", "Math Doc",
            "This document has a blocked word in it.");
        state = state.withResult(ResourceType.DOC, doc);

        // Execute
        GenerationState result = reviewAgent.execute(state);

        // Verify: first retry, newRetries=1, <= 3 → DESIGN
        assertThat(result.stage()).isEqualTo(ProgressStage.DESIGN);
        assertThat(result.reviewRetries()).isEqualTo(1);
        assertThat(result.reviews()).hasSize(1);
        assertThat(result.reviews().get(0).passed()).isFalse();
    }

    // ---- Test 3: Max retries — already at retry 3 → stage=FALLBACK ----

    @Test
    void shouldRouteToFallbackWhenMaxRetriesExceeded() {
        // L1: blocked keyword
        reviewKeywordsConfig.setKeywords(List.of("blocked"));

        // L2: LLM review passes
        when(provider.chat(anyString())).thenReturn("passed");

        // Build state with reviewRetries = 3 (already at max)
        GenerateRequest req = new GenerateRequest("student-1", "Math", List.of(ResourceType.DOC));
        GenerationState state = GenerationState.initial(req);

        GeneratedResource doc = GeneratedResource.of(ResourceType.DOC, "Math", "Math Doc",
            "This document has a blocked word in it.");

        // Simulate 3 previous retries: set reviewRetries to 3
        state = new GenerationState(
            state.requestId(), state.studentId(), state.knowledgePoint(),
            state.types(), state.profile(), state.knowledgeContext(), state.ragContext(),
            state.blueprint(),
            Map.of(ResourceType.DOC, doc), // results
            List.of(), // reviews
            3, // reviewRetries already at 3
            ProgressStage.DESIGN, // current stage before review
            null
        );

        // Execute
        GenerationState result = reviewAgent.execute(state);

        // Verify: newRetries = 4 > 3 → FALLBACK
        assertThat(result.stage()).isEqualTo(ProgressStage.FALLBACK);
        assertThat(result.reviewRetries()).isEqualTo(4);
        assertThat(result.reviews()).hasSize(1);
        assertThat(result.reviews().get(0).passed()).isFalse();
    }

    // ---- Test 4: All passed → route to DONE ----

    @Test
    void shouldRouteToDoneWhenAllResourcesPass() {
        // L1: no blocked keywords
        reviewKeywordsConfig.setKeywords(List.of());

        // L2: LLM review passes
        when(provider.chat(anyString())).thenReturn("passed");

        // Build state with clean content
        GenerateRequest req = new GenerateRequest("student-1", "Math", List.of(ResourceType.DOC));
        GenerationState state = GenerationState.initial(req);

        GeneratedResource doc = GeneratedResource.of(ResourceType.DOC, "Math", "Math Tutorial",
            "This is safe educational content about mathematics.");
        state = state.withResult(ResourceType.DOC, doc);

        // Execute
        GenerationState result = reviewAgent.execute(state);

        // Verify: all passed → DONE
        assertThat(result.stage()).isEqualTo(ProgressStage.DONE);
        assertThat(result.reviewRetries()).isEqualTo(1);
        assertThat(result.reviews()).hasSize(1);
        assertThat(result.reviews().get(0).passed()).isTrue();
    }

    // ---- Test 5: Multiple resources with mixed results ----

    @Test
    void shouldHandleMixedPassAndFailResults() {
        // L1: only one keyword blocks
        reviewKeywordsConfig.setKeywords(List.of("violence"));

        // L2: LLM review passes for all
        when(provider.chat(anyString())).thenReturn("passed");

        GenerateRequest req = new GenerateRequest("student-1", "SVM",
            List.of(ResourceType.DOC, ResourceType.MINDMAP));
        GenerationState state = GenerationState.initial(req);

        GeneratedResource doc = GeneratedResource.of(ResourceType.DOC, "SVM", "SVM Doc",
            "This document contains violence and should be blocked.");
        GeneratedResource mindmap = GeneratedResource.of(ResourceType.MINDMAP, "SVM", "SVM Mindmap",
            "mindmap\n  SVM\n    Overview");
        state = state.withResult(ResourceType.DOC, doc)
                     .withResult(ResourceType.MINDMAP, mindmap);

        // Execute
        GenerationState result = reviewAgent.execute(state);

        // Verify: DOC should fail, MINDMAP should pass → overall stage = DESIGN (retry)
        assertThat(result.stage()).isEqualTo(ProgressStage.DESIGN);
        assertThat(result.reviewRetries()).isEqualTo(1);

        // 2 reports (one per resource type)
        assertThat(result.reviews()).hasSize(2);
        QualityReport docReport = result.reviews().get(0);
        QualityReport mmReport = result.reviews().get(1);

        assertThat(docReport.resourceType()).isEqualTo(ResourceType.DOC);
        assertThat(docReport.passed()).isFalse();

        assertThat(mmReport.resourceType()).isEqualTo(ResourceType.MINDMAP);
        assertThat(mmReport.passed()).isTrue();
    }
}
