package org.example.educatorweb.resourcegen.model;

import org.example.educatorweb.common.model.ResourceType;
import java.time.Instant;
import java.util.List;

public record QualityReport(
    String resourceId,
    ResourceType resourceType,
    boolean passed,
    List<QualityIssue> issues,
    int retryCount,
    Instant reviewedAt
) {
    public record QualityIssue(QualityLayer layer, String description, Severity severity) {}
    public enum QualityLayer { L1_KEYWORD, L2_LLM_REVIEW, L3_EXECUTION, L4_MANUAL_FLAG }
    public enum Severity { BLOCK, WARN, INFO }
}
