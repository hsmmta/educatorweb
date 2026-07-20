package org.example.educatorweb.profile.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import org.example.educatorweb.aitutor.config.ChromaClient;
import org.example.educatorweb.profile.ProfileAnalysisService;
import org.example.educatorweb.profile.ProfileService;
import org.example.educatorweb.profile.agent.ProfileUpdateAgent;
import org.example.educatorweb.profile.model.ProfileAnalysisResult;
import org.example.educatorweb.profile.model.StudentProfile;
import org.example.educatorweb.rag.service.EmbeddingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ProfileAnalysisServiceImpl implements ProfileAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(ProfileAnalysisServiceImpl.class);

    private static final int CONVERSATION_LIMIT = 20;
    /** Neutral query text used to retrieve a broad sample of conversations. */
    private static final String NEUTRAL_QUERY = "学生的学习情况和知识水平分析";

    private final ProfileService profileService;
    private final ChromaClient chromaClient;
    private final EmbeddingService embeddingService;
    private final OpenAiChatModel chatModel;
    private final ObjectMapper objectMapper;

    public ProfileAnalysisServiceImpl(ProfileService profileService,
                                      ChromaClient chromaClient,
                                      EmbeddingService embeddingService,
                                      OpenAiChatModel chatModel) {
        this.profileService = profileService;
        this.chromaClient = chromaClient;
        this.embeddingService = embeddingService;
        this.chatModel = chatModel;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public ProfileAnalysisResult analyzeAndUpdate(String studentId) {
        log.info("ProfileAnalysis: starting analysis for student={}", studentId);

        // 1. Fetch current profile (or create a default one)
        StudentProfile currentProfile = fetchOrCreateProfile(studentId);

        // 2. Retrieve recent conversations from Chroma
        List<Map<String, Object>> conversations = retrieveConversations(studentId);

        // 3. Serialize to JSON for the agent prompt
        String currentProfileJson = toJson(safeProfileMap(currentProfile));
        String conversationsJson = toJson(formatConversations(conversations));

        if (conversations.isEmpty()) {
            log.info("ProfileAnalysis: no conversations found for student={}, returning current profile", studentId);
            return buildNoDataResult(currentProfile);
        }

        // 4. Build the agent and call the LLM
        ProfileUpdateAgent agent = AiServices.create(ProfileUpdateAgent.class, chatModel);

        String agentResponse;
        try {
            agentResponse = agent.analyze(currentProfileJson, conversationsJson);
            log.debug("ProfileAnalysis: agent raw response (len={})", agentResponse.length());
        } catch (Exception e) {
            log.error("ProfileAnalysis: agent call failed: {}", e.getMessage());
            return buildNoDataResult(currentProfile);
        }

        // 5. Parse the JSON response
        ProfileAnalysisResult result = parseAgentResponse(agentResponse);
        if (result == null) {
            log.warn("ProfileAnalysis: failed to parse agent response, keeping current profile");
            return buildNoDataResult(currentProfile);
        }

        // 6. Apply updates to the profile
        try {
            StudentProfile updated = applyResult(currentProfile, result, studentId);
            profileService.updateProfile(studentId, updated);
            log.info("ProfileAnalysis: profile updated for student={}, reasoning={}",
                studentId, result.reasoning());
        } catch (Exception e) {
            log.error("ProfileAnalysis: failed to update profile for student={}: {}", studentId, e.getMessage());
        }

        return result;
    }

    // Helpers — data fetching

    private StudentProfile fetchOrCreateProfile(String studentId) {
        StudentProfile profile = profileService.getProfile(studentId);
        if (profile == null) {
            log.info("ProfileAnalysis: no existing profile for student={}, creating default", studentId);
            profile = createDefaultProfile(studentId);
        }
        return profile;
    }

    private StudentProfile createDefaultProfile(String studentId) {
        StudentProfile p = new StudentProfile();
        p.setStudentId(studentId);
        p.setKnowledgeBaseLevel("一般");
        p.setKnowledgeBaseConfidence(new BigDecimal("0.50"));
        p.setCognitiveStyleType("分析型");
        p.setCognitiveStyleConfidence(new BigDecimal("0.50"));
        p.setErrorPatternTags(new ArrayList<>());
        p.setErrorPatternConfidence(new BigDecimal("0.50"));
        p.setLearningPaceType("稳扎稳打型");
        p.setLearningPaceConfidence(new BigDecimal("0.50"));
        p.setContentPreferenceType("混合学习");
        p.setContentPreferenceRatio(Map.of("video", 0.33, "document", 0.34, "code", 0.33));
        p.setGoalOrientationType("兴趣探索");
        p.setGoalOrientationConfidence(new BigDecimal("0.50"));
        return p;
    }

    private List<Map<String, Object>> retrieveConversations(String userId) {
        try {
            float[] raw = embeddingService.embed(NEUTRAL_QUERY);
            if (raw.length == 0) return List.of();
            List<Float> embedding = new ArrayList<>(raw.length);
            for (float f : raw) embedding.add(f);
            return chromaClient.query(embedding, userId, CONVERSATION_LIMIT);
        } catch (Exception e) {
            log.warn("ProfileAnalysis: failed to retrieve conversations: {}", e.getMessage());
            return List.of();
        }
    }

    // Helpers — serialization / formatting

    /** Build a safe serialization-friendly map of profile fields. */
    private Map<String, Object> safeProfileMap(StudentProfile p) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("studentId", p.getStudentId());
        map.put("knowledgeBaseLevel", p.getKnowledgeBaseLevel());
        map.put("knowledgeBaseConfidence", p.getKnowledgeBaseConfidence());
        map.put("cognitiveStyleType", p.getCognitiveStyleType());
        map.put("cognitiveStyleConfidence", p.getCognitiveStyleConfidence());
        map.put("errorPatternTags", p.getErrorPatternTags());
        map.put("errorPatternConfidence", p.getErrorPatternConfidence());
        map.put("learningPaceType", p.getLearningPaceType());
        map.put("learningPaceConfidence", p.getLearningPaceConfidence());
        map.put("contentPreferenceType", p.getContentPreferenceType());
        map.put("contentPreferenceRatio", p.getContentPreferenceRatio());
        map.put("goalOrientationType", p.getGoalOrientationType());
        map.put("goalOrientationConfidence", p.getGoalOrientationConfidence());
        return map;
    }

    /**
     * Format Chroma query results into a readable conversation transcript.
     * Returns a list of {role, text, timestamp} maps.
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> formatConversations(List<Map<String, Object>> records) {
        // Sort by timestamp ascending
        records.sort((a, b) -> {
            Map<String, Object> ma = (Map<String, Object>) a.get("metadata");
            Map<String, Object> mb = (Map<String, Object>) b.get("metadata");
            String ta = ma != null ? String.valueOf(ma.getOrDefault("timestamp", "")) : "";
            String tb = mb != null ? String.valueOf(mb.getOrDefault("timestamp", "")) : "";
            return ta.compareTo(tb);
        });

        List<Map<String, Object>> formatted = new ArrayList<>();
        for (Map<String, Object> record : records) {
            Map<String, Object> meta = (Map<String, Object>) record.get("metadata");
            String role = meta != null ? String.valueOf(meta.getOrDefault("role", "")) : "";
            String label = role.contains("user") ? "学生提问" : "AI回答";
            formatted.add(Map.of(
                "role", label,
                "text", record.getOrDefault("document", ""),
                "timestamp", meta != null ? meta.getOrDefault("timestamp", "") : ""
            ));
        }
        return formatted;
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    // Helpers — parsing & applying results

    private ProfileAnalysisResult parseAgentResponse(String response) {
        try {
            // Strip markdown code fences if present
            String json = response.trim();
            if (json.startsWith("```")) {
                json = json.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();
            }
            return objectMapper.readValue(json, ProfileAnalysisResult.class);
        } catch (Exception e) {
            log.warn("ProfileAnalysis: JSON parse error: {}", e.getMessage());
            return null;
        }
    }

    private StudentProfile applyResult(StudentProfile current, ProfileAnalysisResult result, String studentId) {
        StudentProfile p = new StudentProfile();
        p.setStudentId(studentId);

        // Apply updated values (agent output), fall back to current values if null
        p.setKnowledgeBaseLevel(
            result.knowledgeBaseLevel() != null ? result.knowledgeBaseLevel() : current.getKnowledgeBaseLevel());
        p.setKnowledgeBaseConfidence(
            result.knowledgeBaseConfidence() != null ? result.knowledgeBaseConfidence() : current.getKnowledgeBaseConfidence());
        p.setCognitiveStyleType(
            result.cognitiveStyleType() != null ? result.cognitiveStyleType() : current.getCognitiveStyleType());
        p.setCognitiveStyleConfidence(
            result.cognitiveStyleConfidence() != null ? result.cognitiveStyleConfidence() : current.getCognitiveStyleConfidence());
        p.setErrorPatternTags(
            result.errorPatternTags() != null ? result.errorPatternTags() : current.getErrorPatternTags());
        p.setErrorPatternConfidence(
            result.errorPatternConfidence() != null ? result.errorPatternConfidence() : current.getErrorPatternConfidence());
        p.setLearningPaceType(
            result.learningPaceType() != null ? result.learningPaceType() : current.getLearningPaceType());
        p.setLearningPaceConfidence(
            result.learningPaceConfidence() != null ? result.learningPaceConfidence() : current.getLearningPaceConfidence());
        p.setContentPreferenceType(
            result.contentPreferenceType() != null ? result.contentPreferenceType() : current.getContentPreferenceType());
        p.setContentPreferenceRatio(
            result.contentPreferenceRatio() != null ? result.contentPreferenceRatio() : current.getContentPreferenceRatio());
        p.setGoalOrientationType(
            result.goalOrientationType() != null ? result.goalOrientationType() : current.getGoalOrientationType());
        p.setGoalOrientationConfidence(
            result.goalOrientationConfidence() != null ? result.goalOrientationConfidence() : current.getGoalOrientationConfidence());

        // Preserve existing knowledge proficiency details from current profile
        if (current.getKnowledgeDetails() != null) {
            p.setKnowledgeDetails(new ArrayList<>(current.getKnowledgeDetails()));
        }

        return p;
    }

    private ProfileAnalysisResult buildNoDataResult(StudentProfile p) {
        return new ProfileAnalysisResult(
            p.getKnowledgeBaseLevel(), p.getKnowledgeBaseConfidence(),
            p.getCognitiveStyleType(), p.getCognitiveStyleConfidence(),
            p.getErrorPatternTags(), p.getErrorPatternConfidence(),
            p.getLearningPaceType(), p.getLearningPaceConfidence(),
            p.getContentPreferenceType(), p.getContentPreferenceRatio(),
            p.getGoalOrientationType(), p.getGoalOrientationConfidence(),
            "暂无足够的对话数据进行分析，保持当前画像不变。"
        );
    }
}
