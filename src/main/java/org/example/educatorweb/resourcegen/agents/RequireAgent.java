package org.example.educatorweb.resourcegen.agents;

import org.example.educatorweb.knowledgegraph.KnowledgeGraphService;
import org.example.educatorweb.knowledgegraph.model.KnowledgeContext;
import org.example.educatorweb.profile.ProfileService;
import org.example.educatorweb.profile.model.StudentProfile;
import org.example.educatorweb.rag.RagService;
import org.example.educatorweb.rag.model.DocumentSnippet;
import org.example.educatorweb.resourcegen.model.GenerationState;
import org.example.educatorweb.resourcegen.model.ProgressStage;
import org.example.educatorweb.resourcegen.orchestration.AgentNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class RequireAgent implements AgentNode {
    private static final Logger log = LoggerFactory.getLogger(RequireAgent.class);

    private final ProfileService profileService;
    private final KnowledgeGraphService kgService;
    private final RagService ragService;

    public RequireAgent(ProfileService profileService, KnowledgeGraphService kgService, RagService ragService) {
        this.profileService = profileService;
        this.kgService = kgService;
        this.ragService = ragService;
    }

    @Override
    public GenerationState execute(GenerationState state) {
        log.info("RequireAgent: enriching context for student={}, topic={}", state.studentId(), state.knowledgePoint());

        var profile = fetchProfile(state.studentId());
        var kgContext = fetchKnowledgeContext(state.knowledgePoint());
        var ragContext = fetchRagContext(state.knowledgePoint());

        return state.withContext(kgContext, ragContext)
                    .withProfile(profile)
                    .withStage(ProgressStage.DESIGN);
    }

    private StudentProfile fetchProfile(String studentId) {
        try {
            return profileService.getProfile(studentId);
        } catch (Exception e) {
            log.warn("RequireAgent: failed to fetch profile for student={}: {}", studentId, e.getMessage());
            return null;
        }
    }

    private KnowledgeContext fetchKnowledgeContext(String knowledgePoint) {
        try {
            return kgService.queryContext(knowledgePoint);
        } catch (Exception e) {
            log.warn("RequireAgent: failed to fetch knowledge context for topic={}: {}", knowledgePoint, e.getMessage());
            return null;
        }
    }

    private List<DocumentSnippet> fetchRagContext(String knowledgePoint) {
        try {
            return ragService.retrieve(knowledgePoint, 5);
        } catch (Exception e) {
            log.warn("RequireAgent: failed to fetch RAG context for topic={}: {}", knowledgePoint, e.getMessage());
            return List.of();
        }
    }
}
