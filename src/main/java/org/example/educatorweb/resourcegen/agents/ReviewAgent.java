package org.example.educatorweb.resourcegen.agents;

import org.example.educatorweb.common.model.ResourceType;
import org.example.educatorweb.resourcegen.config.ModelRegistry;
import org.example.educatorweb.resourcegen.config.ReviewKeywordsConfig;
import org.example.educatorweb.resourcegen.infrastructure.ModelProvider;
import org.example.educatorweb.resourcegen.model.GeneratedResource;
import org.example.educatorweb.resourcegen.model.GenerationState;
import org.example.educatorweb.resourcegen.model.ProgressStage;
import org.example.educatorweb.resourcegen.model.QualityReport;
import org.example.educatorweb.resourcegen.model.QualityReport.QualityIssue;
import org.example.educatorweb.resourcegen.model.QualityReport.QualityLayer;
import org.example.educatorweb.resourcegen.model.QualityReport.Severity;
import org.example.educatorweb.resourcegen.orchestration.AgentNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class ReviewAgent implements AgentNode {
    private static final Logger log = LoggerFactory.getLogger(ReviewAgent.class);
    private static final int MAX_RETRIES = 3;

    private final ModelRegistry registry;
    private final ReviewKeywordsConfig reviewKeywordsConfig;

    public ReviewAgent(ModelRegistry registry, ReviewKeywordsConfig reviewKeywordsConfig) {
        this.registry = registry;
        this.reviewKeywordsConfig = reviewKeywordsConfig;
    }

    @Override
    public GenerationState execute(GenerationState state) {
        log.info("ReviewAgent: reviewing {} resource(s), retry={}/{}",
            state.results().size(), state.reviewRetries(), MAX_RETRIES);

        List<QualityReport> reports = new ArrayList<>();

        for (Map.Entry<ResourceType, GeneratedResource> entry : state.results().entrySet()) {
            ResourceType type = entry.getKey();
            GeneratedResource resource = entry.getValue();
            List<QualityIssue> issues = new ArrayList<>();

            // L1: Keyword filtering
            List<QualityIssue> l1Issues = reviewL1Keywords(resource);
            issues.addAll(l1Issues);

            // L2: LLM review for accuracy and relevance
            List<QualityIssue> l2Issues = reviewL2Llm(state, resource);
            issues.addAll(l2Issues);

            // L3: Placeholder for execution validation (Task 8)
            // L4: Placeholder for manual flag (future)

            boolean passed = issues.stream().noneMatch(i -> i.severity() == Severity.BLOCK);
            reports.add(new QualityReport(
                resource.resourceId(), type, passed, List.copyOf(issues),
                state.reviewRetries(), Instant.now()
            ));

            log.info("ReviewAgent: {} → passed={}, issues={}", type, passed, issues.size());
        }

        int newRetries = state.reviewRetries() + 1;
        boolean allPassed = reports.stream().allMatch(QualityReport::passed);

        // Build combined review list (existing + new)
        List<QualityReport> allReports = new ArrayList<>(state.reviews());
        allReports.addAll(reports);

        // Determine next stage. VIDEO is never retried — it's too expensive and visual content
        // cannot be meaningfully reviewed by a text LLM.
        boolean hasVideoOnlyFailure = state.results().keySet().stream()
            .allMatch(t -> t == ResourceType.VIDEO)
            && !allPassed;

        ProgressStage nextStage;
        if (allPassed) {
            nextStage = ProgressStage.DONE;
            log.info("ReviewAgent: all resources passed review, routing to DONE");
        } else if (hasVideoOnlyFailure) {
            nextStage = ProgressStage.DONE; // don't retry video — too expensive
            log.warn("ReviewAgent: VIDEO failed review but skipping retry (video generation is expensive)");
        } else if (newRetries <= MAX_RETRIES) {
            nextStage = ProgressStage.DESIGN;
            log.info("ReviewAgent: some resources failed, retry {}/{} → routing to DESIGN",
                newRetries, MAX_RETRIES);
        } else {
            nextStage = ProgressStage.FALLBACK;
            log.warn("ReviewAgent: max retries ({}) exceeded, routing to FALLBACK", MAX_RETRIES);
        }

        return state.withReviews(allReports, newRetries).withStage(nextStage);
    }

    // ---- L1: Keyword-based content filtering ----
    private List<QualityIssue> reviewL1Keywords(GeneratedResource resource) {
        List<QualityIssue> issues = new ArrayList<>();
        String content = resource.content();
        if (content == null || content.isBlank()) {
            return issues;
        }

        String lowerContent = content.toLowerCase();
        for (String keyword : reviewKeywordsConfig.getKeywords()) {
            if (keyword != null && !keyword.isBlank() && lowerContent.contains(keyword.toLowerCase())) {
                issues.add(new QualityIssue(
                    QualityLayer.L1_KEYWORD,
                    "Content contains blocked keyword: \"" + keyword + "\"",
                    Severity.BLOCK
                ));
                log.warn("ReviewAgent L1: blocked keyword \"{}\" found in {}", keyword, resource.type());
            }
        }

        return issues;
    }

    // ---- L2: LLM-based accuracy and relevance review ----
    private List<QualityIssue> reviewL2Llm(GenerationState state, GeneratedResource resource) {
        List<QualityIssue> issues = new ArrayList<>();
        String content = resource.content();
        if (content == null || content.isBlank()) {
            issues.add(new QualityIssue(
                QualityLayer.L2_LLM_REVIEW,
                "Resource content is empty",
                Severity.BLOCK
            ));
            return issues;
        }

        try {
            String prompt = buildL2ReviewPrompt(state.knowledgePoint(), resource.type(), content);
            ModelProvider provider = registry.resolve(ResourceType.DOC); // always use text model for review
            String response = provider.chat(prompt);

            if (response == null || response.isBlank()) {
                issues.add(new QualityIssue(
                    QualityLayer.L2_LLM_REVIEW,
                    "LLM review returned empty response",
                    Severity.WARN
                ));
                return issues;
            }

            // Parse LLM review verdict
            String lowerResponse = response.toLowerCase();
            if (lowerResponse.contains("rejected") || lowerResponse.contains("block")) {
                issues.add(new QualityIssue(
                    QualityLayer.L2_LLM_REVIEW,
                    "LLM review flagged content as problematic: " + truncate(response, 200),
                    Severity.BLOCK
                ));
            } else if (lowerResponse.contains("warn")) {
                issues.add(new QualityIssue(
                    QualityLayer.L2_LLM_REVIEW,
                    "LLM review issued warning: " + truncate(response, 200),
                    Severity.WARN
                ));
            } else {
                // Passed or no issues found
                issues.add(new QualityIssue(
                    QualityLayer.L2_LLM_REVIEW,
                    "LLM review passed: " + truncate(response, 200),
                    Severity.INFO
                ));
            }
        } catch (Exception e) {
            log.error("ReviewAgent L2: LLM review failed for {}: {}", resource.type(), e.getMessage());
            issues.add(new QualityIssue(
                QualityLayer.L2_LLM_REVIEW,
                "LLM review error: " + e.getMessage(),
                Severity.WARN
            ));
        }

        return issues;
    }

    private String buildL2ReviewPrompt(String knowledgePoint, ResourceType type, String content) {
        // Truncate content to avoid excessively long prompts
        String truncatedContent = content.length() > 4000 ? content.substring(0, 4000) + "..." : content;

        return String.format("""
            你是一位教育内容审核专家。请审查以下教育资源的准确性和相关性。

            ## 目标知识点
            %s

            ## 资源类型
            %s

            ## 资源内容（已截断）
            %s

            ## 审查标准
            1. **准确性**: 内容在学术和教学上是否正确无误？
            2. **相关性**: 内容是否紧扣目标知识点？
            3. **适当性**: 内容难度和表述是否适合教学场景？
            4. **完整性**: 内容结构是否完整、逻辑是否清晰？

            ## 输出要求
            请用以下格式之一回复（仅回复一个词或简单短语）：
            - 如果内容没有问题，回复: "passed"
            - 如果内容有轻微问题但不影响使用，回复: "warn: <简要说明>"
            - 如果内容存在严重错误或完全偏离主题，回复: "rejected: <简要说明>"
            """, knowledgePoint, type.name(), truncatedContent);
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
