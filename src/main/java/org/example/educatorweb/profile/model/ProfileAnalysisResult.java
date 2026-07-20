package org.example.educatorweb.profile.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Structured result produced by the {@link org.example.educatorweb.profile.agent.ProfileUpdateAgent}
 * after analyzing student conversations.
 *
 * Mirrors the JSON schema expected from the LLM agent.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProfileAnalysisResult(
    @JsonProperty("knowledgeBaseLevel") String knowledgeBaseLevel,
    @JsonProperty("knowledgeBaseConfidence") BigDecimal knowledgeBaseConfidence,
    @JsonProperty("cognitiveStyleType") String cognitiveStyleType,
    @JsonProperty("cognitiveStyleConfidence") BigDecimal cognitiveStyleConfidence,
    @JsonProperty("errorPatternTags") List<String> errorPatternTags,
    @JsonProperty("errorPatternConfidence") BigDecimal errorPatternConfidence,
    @JsonProperty("learningPaceType") String learningPaceType,
    @JsonProperty("learningPaceConfidence") BigDecimal learningPaceConfidence,
    @JsonProperty("contentPreferenceType") String contentPreferenceType,
    @JsonProperty("contentPreferenceRatio") Map<String, Double> contentPreferenceRatio,
    @JsonProperty("goalOrientationType") String goalOrientationType,
    @JsonProperty("goalOrientationConfidence") BigDecimal goalOrientationConfidence,
    @JsonProperty("reasoning") String reasoning
) {}
