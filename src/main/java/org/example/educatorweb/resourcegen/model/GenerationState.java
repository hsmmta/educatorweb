package org.example.educatorweb.resourcegen.model;

import org.example.educatorweb.common.model.GenerateRequest;
import org.example.educatorweb.common.model.ResourceType;
import org.example.educatorweb.knowledgegraph.model.KnowledgeContext;
import org.example.educatorweb.profile.model.StudentProfile;
import org.example.educatorweb.rag.model.DocumentSnippet;
import java.util.*;

public record GenerationState(
    String requestId, String studentId, String knowledgePoint,
    List<ResourceType> types,
    StudentProfile profile,
    KnowledgeContext knowledgeContext,
    List<DocumentSnippet> ragContext,
    ResourceBlueprint blueprint,
    Map<ResourceType, GeneratedResource> results,
    List<QualityReport> reviews,
    int reviewRetries,
    ProgressStage stage,
    String error
) {
    // Placeholder types (replaced in Task 4)
    public record ResourceBlueprint(String placeholder) {}
    public record GeneratedResource(String placeholder) {}
    public record QualityReport(String placeholder) {}

    public static GenerationState initial(GenerateRequest req) {
        return new GenerationState(
            UUID.randomUUID().toString(), req.studentId(), req.knowledgePoint(),
            req.types(), null, null, null, null,
            new HashMap<>(), new ArrayList<>(), 0,
            ProgressStage.INIT, null
        );
    }

    public GenerationState withStage(ProgressStage s) {
        return new GenerationState(requestId, studentId, knowledgePoint, types,
            profile, knowledgeContext, ragContext, blueprint,
            results, reviews, reviewRetries, s, error);
    }

    public GenerationState withProfile(StudentProfile p) {
        return new GenerationState(requestId, studentId, knowledgePoint, types,
            p, knowledgeContext, ragContext, blueprint,
            results, reviews, reviewRetries, stage, error);
    }

    public GenerationState withContext(KnowledgeContext kc, List<DocumentSnippet> rag) {
        return new GenerationState(requestId, studentId, knowledgePoint, types,
            profile, kc, rag, blueprint,
            results, reviews, reviewRetries, stage, error);
    }

    public GenerationState withBlueprint(ResourceBlueprint bp) {
        return new GenerationState(requestId, studentId, knowledgePoint, types,
            profile, knowledgeContext, ragContext, bp,
            results, reviews, reviewRetries, stage, error);
    }

    public GenerationState withResults(Map<ResourceType, GeneratedResource> r) {
        return new GenerationState(requestId, studentId, knowledgePoint, types,
            profile, knowledgeContext, ragContext, blueprint,
            r, reviews, reviewRetries, stage, error);
    }

    public GenerationState withReview(QualityReport review) {
        List<QualityReport> newReviews = new ArrayList<>(reviews);
        newReviews.add(review);
        return new GenerationState(requestId, studentId, knowledgePoint, types,
            profile, knowledgeContext, ragContext, blueprint,
            results, newReviews, reviewRetries + 1, stage, error);
    }

    public GenerationState withError(String err) {
        return new GenerationState(requestId, studentId, knowledgePoint, types,
            profile, knowledgeContext, ragContext, blueprint,
            results, reviews, reviewRetries, ProgressStage.FALLBACK, err);
    }
}
