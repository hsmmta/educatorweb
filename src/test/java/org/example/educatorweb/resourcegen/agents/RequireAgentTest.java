package org.example.educatorweb.resourcegen.agents;

import org.example.educatorweb.knowledgegraph.KnowledgeGraphService;
import org.example.educatorweb.knowledgegraph.model.KnowledgeContext;
import org.example.educatorweb.profile.ProfileService;
import org.example.educatorweb.profile.model.StudentProfile;
import org.example.educatorweb.rag.RagService;
import org.example.educatorweb.rag.model.DocumentSnippet;
import org.example.educatorweb.common.model.GenerateRequest;
import org.example.educatorweb.common.model.ResourceType;
import org.example.educatorweb.resourcegen.model.GenerationState;
import org.example.educatorweb.resourcegen.model.ProgressStage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RequireAgentTest {

    @Mock
    private ProfileService profileService;

    @Mock
    private KnowledgeGraphService kgService;

    @Mock
    private RagService ragService;

    @InjectMocks
    private RequireAgent requireAgent;

    private static StudentProfile testProfile() {
        StudentProfile p = new StudentProfile();
        p.setKnowledgeBaseLevel("一般");
        p.setKnowledgeBaseConfidence(new java.math.BigDecimal("0.85"));
        p.setCognitiveStyleType("直觉型");
        p.setCognitiveStyleConfidence(new java.math.BigDecimal("0.72"));
        p.setErrorPatternTags(List.of("过拟合概念混淆"));
        p.setErrorPatternConfidence(new java.math.BigDecimal("0.68"));
        p.setLearningPaceType("稳扎稳打型");
        p.setLearningPaceConfidence(new java.math.BigDecimal("0.90"));
        p.setContentPreferenceType("混合学习");
        p.setContentPreferenceRatio(Map.of("video", 0.4, "document", 0.35));
        p.setGoalOrientationType("求职准备");
        p.setGoalOrientationConfidence(new java.math.BigDecimal("0.88"));
        return p;
    }

    // ---- Test 1: All services succeed, state fully populated ----

    @Test
    void shouldFetchAllContextsAndPopulateState() {
        var profile = testProfile();
        var kgContext = new KnowledgeContext(
            List.of("线性回归"), List.of("核方法"),
            List.of("支持向量", "核函数"), 3);
        var snippets = List.of(
            new DocumentSnippet("SVM的核心思想...", "教材-第6章", 0.95)
        );

        when(profileService.getProfile("student-1")).thenReturn(profile);
        when(kgService.queryContext("SVM")).thenReturn(kgContext);
        when(ragService.retrieve("student-1", "SVM", 5)).thenReturn(snippets);

        GenerateRequest req = new GenerateRequest("student-1", "SVM", List.of(ResourceType.DOC));
        GenerationState state = GenerationState.initial(req);

        GenerationState result = requireAgent.execute(state);

        assertThat(result.profile()).isSameAs(profile);
        assertThat(result.knowledgeContext()).isSameAs(kgContext);
        assertThat(result.ragContext()).isSameAs(snippets);
        assertThat(result.stage()).isEqualTo(ProgressStage.DESIGN);

        verify(profileService).getProfile("student-1");
        verify(kgService).queryContext("SVM");
        verify(ragService).retrieve("student-1", "SVM", 5);
    }

    // ---- Test 2: Profile service fails — graceful degradation ----

    @Test
    void shouldHandleProfileServiceFailureGracefully() {
        when(profileService.getProfile(anyString()))
            .thenThrow(new RuntimeException("DB connection lost"));
        when(kgService.queryContext("SVM"))
            .thenReturn(new KnowledgeContext(List.of(), List.of(), List.of(), 1));
        when(ragService.retrieve(anyString(), anyString(), anyInt()))
            .thenReturn(List.of());

        GenerateRequest req = new GenerateRequest("student-1", "SVM", List.of(ResourceType.DOC));
        GenerationState state = GenerationState.initial(req);

        GenerationState result = requireAgent.execute(state);

        // Profile should be null (graceful degradation), but others should be populated
        assertThat(result.profile()).isNull();
        assertThat(result.knowledgeContext()).isNotNull();
        assertThat(result.ragContext()).isNotNull();
        assertThat(result.stage()).isEqualTo(ProgressStage.DESIGN);
        assertThat(result.error()).isNull(); // No error on state — just degraded
    }

    // ---- Test 3: KnowledgeGraph service fails — graceful degradation ----

    @Test
    void shouldHandleKnowledgeGraphFailureGracefully() {
        var profile = testProfile();
        when(profileService.getProfile("student-1")).thenReturn(profile);
        when(kgService.queryContext(anyString()))
            .thenThrow(new RuntimeException("KG unavailable"));
        when(ragService.retrieve(anyString(), anyString(), anyInt()))
            .thenReturn(List.of());

        GenerateRequest req = new GenerateRequest("student-1", "SVM", List.of(ResourceType.DOC));
        GenerationState state = GenerationState.initial(req);

        GenerationState result = requireAgent.execute(state);

        assertThat(result.profile()).isNotNull();
        assertThat(result.knowledgeContext()).isNull();
        assertThat(result.ragContext()).isNotNull();
        assertThat(result.stage()).isEqualTo(ProgressStage.DESIGN);
    }

    // ---- Test 4: RAG service fails — graceful degradation ----

    @Test
    void shouldHandleRagServiceFailureGracefully() {
        var profile = testProfile();
        var kgContext = new KnowledgeContext(List.of(), List.of(), List.of(), 1);

        when(profileService.getProfile("student-1")).thenReturn(profile);
        when(kgService.queryContext("SVM")).thenReturn(kgContext);
        when(ragService.retrieve(anyString(), anyString(), anyInt()))
            .thenThrow(new RuntimeException("RAG index unavailable"));

        GenerateRequest req = new GenerateRequest("student-1", "SVM", List.of(ResourceType.DOC));
        GenerationState state = GenerationState.initial(req);

        GenerationState result = requireAgent.execute(state);

        assertThat(result.profile()).isNotNull();
        assertThat(result.knowledgeContext()).isNotNull();
        assertThat(result.ragContext()).isEmpty(); // Returns empty list on failure
        assertThat(result.stage()).isEqualTo(ProgressStage.DESIGN);
    }
}
