package org.example.educatorweb.profile.chat;

import org.example.educatorweb.profile.ProfileService;
import org.example.educatorweb.profile.ProfileValueNormalizer;
import org.example.educatorweb.profile.chat.ProfileExtractionAgent.DimensionDef;
import org.example.educatorweb.profile.chat.ProfileExtractionAgent.DimensionValue;
import org.example.educatorweb.profile.chat.ProfileExtractionAgent.ProfileExtractionResult;
import org.example.educatorweb.profile.model.StudentProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 对话式画像构建服务。
 * 编排对话流程：管理会话 → 生成提问 → 收集回答 → 立即触发LLM提取 → 更新画像。
 *
 * 核心改进：
 * 1. 每轮学生回答后立即尝试提取画像（不再等待3轮）
 * 2. 提问按维度轮转（knowledge → cognitive → error → pace → preference → goal）
 * 3. 快速更新总是触发完整提取和持久化
 */
@Service
public class ChatProfileService {

    private static final Logger log = LoggerFactory.getLogger(ChatProfileService.class);

    private final ChatSessionRepository sessionRepo;
    private final ChatMessageRepository messageRepo;
    private final ProfileExtractionAgent extractionAgent;
    private final ProfileService profileService;

    public ChatProfileService(ChatSessionRepository sessionRepo,
                              ChatMessageRepository messageRepo,
                              ProfileExtractionAgent extractionAgent,
                              ProfileService profileService) {
        this.sessionRepo = sessionRepo;
        this.messageRepo = messageRepo;
        this.extractionAgent = extractionAgent;
        this.profileService = profileService;
    }

    /**
     * 开始一次新的画像构建会话。
     */
    @Transactional
    public ChatSession startSession(String studentId, String sessionType) {
        String sessionId = UUID.randomUUID().toString();
        ChatSession session = new ChatSession(sessionId, studentId, sessionType);
        session = sessionRepo.save(session);
        log.info("Started profile chat session: {} type={} for student: {}", sessionId, sessionType, studentId);
        return session;
    }

    /**
     * 获取系统对学生的下一个提问。
     */
    @Transactional
    public String getNextQuestion(String sessionId) {
        ChatSession session = sessionRepo.findById(sessionId)
            .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));

        StudentProfile currentProfile = profileService.getProfile(session.getStudentId());
        Set<String> completedDims = parseCompletedDimensions(session.getCompletedDimensions());
        List<ChatMessage> messages = messageRepo.findBySessionIdOrderBySequenceAsc(sessionId);
        int round = (int) messages.stream().filter(m -> "STUDENT".equals(m.getRole())).count();
        String conversationHistory = buildConversationHistory(messages);

        return extractionAgent.generateNextQuestion(
            conversationHistory, currentProfile, completedDims, round);
    }

    @Transactional
    public ChatResponse processStudentAnswer(String sessionId, String studentAnswer) {
        ChatSession session = sessionRepo.findById(sessionId)
            .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));

        List<ChatMessage> messages = messageRepo.findBySessionIdOrderBySequenceAsc(sessionId);
        int nextSeq = messages.size();
        ChatMessage studentMsg = new ChatMessage(sessionId, nextSeq, "STUDENT", studentAnswer, null);
        messageRepo.save(studentMsg);
        messages.add(studentMsg);

        String conversationHistory = buildConversationHistory(messages);
        int totalRounds = (int) messages.stream().filter(m -> "STUDENT".equals(m.getRole())).count();

        Set<String> completedDims = parseCompletedDimensions(session.getCompletedDimensions());
        StudentProfile existingProfile = profileService.getProfile(session.getStudentId());

        log.info("ChatProfileService: round={}, extracting profile from {} messages", totalRounds, messages.size());
        ProfileExtractionResult result = extractionAgent.extract(
            conversationHistory, existingProfile, completedDims);

        Set<String> newCompletedDims = result.completedDimensions();
        completedDims.addAll(newCompletedDims);
        session.setCompletedDimensions(String.join(",", completedDims));

        StudentProfile profile = existingProfile != null ? existingProfile : createEmptyProfile(session.getStudentId());
        result.mergeInto(profile);
        // 确保 studentId 不为空
        if (profile.getStudentId() == null || profile.getStudentId().isBlank()) {
            profile.setStudentId(session.getStudentId());
        }
        // 归一化 LLM 输出为 DB CHECK 约束允许的值
        sanitizeProfileForDb(profile);

        // 6. 【关键改进】始终持久化画像（独立事务）
        profileService.updateProfile(session.getStudentId(), profile);
        session.setProfileExtracted(!completedDims.isEmpty());
        log.info("ChatProfileService: profile saved for student={}, completedDims={}, totalRounds={}",
            session.getStudentId(), completedDims, totalRounds);

        session.setUpdatedAt(LocalDateTime.now());
        sessionRepo.save(session);

        boolean allDimsCompleted = completedDims.size() >= ProfileExtractionAgent.DIMENSIONS.size();

        boolean enoughRounds = totalRounds >= ProfileExtractionAgent.DIMENSIONS.size();

        if (allDimsCompleted || (enoughRounds && completedDims.size() >= 4)) {
            String summary = generateProfileSummary(session.getStudentId());
            ChatMessage sysMsg = new ChatMessage(sessionId, nextSeq + 1, "SYSTEM", summary, null);
            messageRepo.save(sysMsg);
            return new ChatResponse(summary, true, completedDims);
        } else {
            String nextQuestion = extractionAgent.generateNextQuestion(
                conversationHistory,
                profileService.getProfile(session.getStudentId()),
                completedDims,
                totalRounds);
            ChatMessage sysMsg = new ChatMessage(sessionId, nextSeq + 1, "SYSTEM", nextQuestion, null);
            messageRepo.save(sysMsg);
            return new ChatResponse(nextQuestion, false, completedDims);
        }
    }

    /**
     * 快速更新：学生直接输入描述，AI 一次性提取并持久化。
     * 与对话模式不同，这里立即触发完整提取。
     */
    @Transactional
    public ChatResponse quickExtract(String studentId, String description) {
        log.info("ChatProfileService: quickExtract for student={}, desc length={}",
            studentId, description.length());

        // 直接构建对话历史（单轮）
        String conversationHistory = "学生: " + description + "\n\n";
        StudentProfile existingProfile = profileService.getProfile(studentId);
        Set<String> completedDims = new HashSet<>();

        // 立即提取
        ProfileExtractionResult result = extractionAgent.extract(
            conversationHistory, existingProfile, completedDims);

        Set<String> newCompletedDims = result.completedDimensions();
        log.info("ChatProfileService: quickExtract completed dims={}", newCompletedDims);

        // 合并并持久化
        StudentProfile profile = existingProfile != null ? existingProfile : createEmptyProfile(studentId);
        result.mergeInto(profile);
        if (profile.getStudentId() == null || profile.getStudentId().isBlank()) {
            profile.setStudentId(studentId);
        }
        // 归一化 LLM 输出为 DB CHECK 约束允许的值
        sanitizeProfileForDb(profile);

        profileService.updateProfile(studentId, profile);
        log.info("ChatProfileService: quickExtract profile saved for student={}", studentId);

        if (newCompletedDims.size() >= 4) {
            // 大部分维度已识别，生成总结
            String summary = generateProfileSummary(studentId);
            return new ChatResponse(summary, true, newCompletedDims);
        } else if (newCompletedDims.isEmpty()) {
            return new ChatResponse(
                "根据你的描述，我暂时还无法构建完整的画像。能否再详细描述一下你的学习情况？比如你的专业、年级、学习目标、偏好的学习方式等。",
                false, newCompletedDims);
        } else {
            // 部分识别成功，提示哪些维度需要补充
            StringBuilder sb = new StringBuilder();
            sb.append("根据你的描述，我已经了解了以下方面：\n\n");
            for (DimensionDef dim : ProfileExtractionAgent.DIMENSIONS) {
                if (newCompletedDims.contains(dim.key())) {
                    sb.append("✅ ").append(dim.icon()).append(" ").append(dim.label()).append("\n");
                } else {
                    sb.append("❓ ").append(dim.icon()).append(" ").append(dim.label()).append(" — 还需要更多信息\n");
                }
            }
            sb.append("\n你可以继续补充描述，或者切换到对话模式逐步完善画像。");
            return new ChatResponse(sb.toString(), false, newCompletedDims);
        }
    }

    // ========== 查询方法 ==========

    @Transactional(readOnly = true)
    public List<ChatMessage> getSessionMessages(String sessionId) {
        return messageRepo.findBySessionIdOrderBySequenceAsc(sessionId);
    }

    @Transactional(readOnly = true)
    public Optional<ChatSession> getLatestSession(String studentId, String sessionType) {
        return sessionRepo.findFirstByStudentIdAndSessionTypeOrderByCreatedAtDesc(studentId, sessionType);
    }

    // ========== 私有方法 ==========

    private String buildConversationHistory(List<ChatMessage> messages) {
        StringBuilder sb = new StringBuilder();
        for (ChatMessage msg : messages) {
            String roleLabel = "SYSTEM".equals(msg.getRole()) ? "AI助手" : "学生";
            sb.append(roleLabel).append(": ").append(msg.getContent()).append("\n\n");
        }
        return sb.toString();
    }

    private Set<String> parseCompletedDimensions(String csv) {
        if (csv == null || csv.isBlank()) return new HashSet<>();
        return new HashSet<>(Arrays.asList(csv.split(",")));
    }

    private String generateProfileSummary(String studentId) {
        StudentProfile profile = profileService.getProfile(studentId);
        if (profile == null) return "画像构建完成！你可以在个人中心查看。";

        StringBuilder sb = new StringBuilder();
        sb.append("🎉 太棒了！你的学习画像已经构建完成，以下是你的6维画像总结：\n\n");

        int validCount = 0;
        for (DimensionDef dim : ProfileExtractionAgent.DIMENSIONS) {
            String value = getHumanReadableValue(profile, dim.key());
            sb.append(String.format("**%s %s**：%s\n\n", dim.icon(), dim.label(), value));
            if (!value.contains("暂未")) validCount++;
        }

        sb.append("---\n\n");
        if (validCount >= 4) {
            sb.append("✅ 系统将根据你的画像为你个性化生成学习资源和规划学习路径。\n");
            sb.append("在学习过程中，画像会持续更新，越来越精准！🚀");
        } else {
            sb.append("⚠️ 部分维度信息不足，建议在后续学习中继续完善。\n");
            sb.append("你可以在个人中心随时更新画像。");
        }

        return sb.toString();
    }

    private String getHumanReadableValue(StudentProfile p, String dimKey) {
        return switch (dimKey) {
            case "knowledge" -> {
                String v = p.getKnowledgeBaseLevel();
                yield (v != null && !v.isBlank())
                    ? v + "（置信度 " + fmtConf(p.getKnowledgeBaseConfidence()) + "）" : "暂未识别";
            }
            case "cognitive" -> {
                String v = p.getCognitiveStyleType();
                yield (v != null && !v.isBlank())
                    ? v + "（置信度 " + fmtConf(p.getCognitiveStyleConfidence()) + "）" : "暂未识别";
            }
            case "error" -> {
                var tags = p.getErrorPatternTags();
                yield (tags != null && !tags.isEmpty()) ? String.join("、", tags) : "暂未识别";
            }
            case "pace" -> {
                String v = p.getLearningPaceType();
                yield (v != null && !v.isBlank())
                    ? v + "（置信度 " + fmtConf(p.getLearningPaceConfidence()) + "）" : "暂未识别";
            }
            case "preference" -> {
                String v = p.getContentPreferenceType();
                if (v == null || v.isBlank()) yield "暂未识别";
                var ratio = p.getContentPreferenceRatio();
                String detail = "";
                if (ratio != null && !ratio.isEmpty()) {
                    detail = "（";
                    for (var entry : ratio.entrySet()) {
                        detail += entry.getKey() + ":" + fmtPct(entry.getValue()) + " ";
                    }
                    detail = detail.trim() + "）";
                }
                yield v + detail;
            }
            case "goal" -> {
                String v = p.getGoalOrientationType();
                yield (v != null && !v.isBlank())
                    ? v + "（置信度 " + fmtConf(p.getGoalOrientationConfidence()) + "）" : "暂未识别";
            }
            default -> "未知";
        };
    }

    private String fmtConf(BigDecimal bd) {
        if (bd == null) return "0";
        return String.format("%d%%", (int) (bd.doubleValue() * 100));
    }

    private String fmtPct(Double d) {
        if (d == null) return "-";
        return String.format("%.0f%%", d * 100);
    }

    // ========== 值规范化（English → Chinese，无 DB CHECK 约束） ==========

    /**
     * 创建新画像，使用中文默认值。
     */
    private StudentProfile createEmptyProfile(String studentId) {
        StudentProfile p = new StudentProfile();
        p.setStudentId(studentId);
        p.setKnowledgeBaseLevel("一般");
        p.setKnowledgeBaseConfidence(new BigDecimal("0.50"));
        p.setCognitiveStyleType("分析型");
        p.setCognitiveStyleConfidence(new BigDecimal("0.50"));
        p.setErrorPatternTags(new ArrayList<>());
        p.setErrorPatternConfidence(new BigDecimal("0.00"));
        p.setLearningPaceType("稳扎稳打型");
        p.setLearningPaceConfidence(new BigDecimal("0.50"));
        p.setContentPreferenceType("混合学习");
        p.setContentPreferenceRatio(new LinkedHashMap<>());
        p.setGoalOrientationType("兴趣探索");
        p.setGoalOrientationConfidence(new BigDecimal("0.50"));
        return p;
    }

    /**
     * 基于结构化表单构建画像。
     * 直接映射 + LLM 辅助分析自由文本字段。
     * "不确定/不知道"的字段维持默认空值。
     */
    @Transactional
    public ChatResponse buildFromForm(ProfileBuildFromFormRequest form) {
        String studentId = form.getStudentId();
        StudentProfile existingProfile = profileService.getProfile(studentId);
        StudentProfile profile = existingProfile != null ? existingProfile : createEmptyProfile(studentId);

        Set<String> completedDims = new HashSet<>();

        // 1. 直接映射结构化字段（表单中文值直接存库）
        if (isValidAnswer(form.getLearningStyle())) {
            profile.setCognitiveStyleType(ProfileValueNormalizer.normalizeCognitiveStyle(form.getLearningStyle()));
            profile.setCognitiveStyleConfidence(new BigDecimal("0.85"));
            completedDims.add("cognitive");
        }

        if (isValidAnswer(form.getLearningPace())) {
            profile.setLearningPaceType(ProfileValueNormalizer.normalizeLearningPace(form.getLearningPace()));
            profile.setLearningPaceConfidence(new BigDecimal("0.85"));
            completedDims.add("pace");
        }

        if (isValidAnswer(form.getLearningGoal())) {
            profile.setGoalOrientationType(ProfileValueNormalizer.normalizeGoalOrientation(form.getLearningGoal()));
            profile.setGoalOrientationConfidence(new BigDecimal("0.90"));
            completedDims.add("goal");
        }

        // 偏好资源类型 → contentPreferenceType + contentPreferenceRatio
        if (form.getPreferredResourceTypes() != null && !form.getPreferredResourceTypes().isEmpty()) {
            Map<String, Double> ratio = new LinkedHashMap<>();
            double perType = 1.0 / form.getPreferredResourceTypes().size();

            for (String rt : form.getPreferredResourceTypes()) {
                String mapped = ProfileValueNormalizer.normalizeContentPreference(rt);
                ratio.put(mapped, Math.round(perType * 100.0) / 100.0);
            }

            double total = ratio.values().stream().mapToDouble(Double::doubleValue).sum();
            if (total > 0) {
                ratio.replaceAll((k, v) -> Math.round(v / total * 100.0) / 100.0);
            }

            profile.setContentPreferenceRatio(ratio);
            String primaryType = form.getPreferredResourceTypes().get(0);
            profile.setContentPreferenceType(ProfileValueNormalizer.normalizeContentPreference(primaryType));
            completedDims.add("preference");
        }

        // 2. LLM 分析自由文本字段（strengths + weaknesses）
        boolean hasStrengths = isValidAnswer(form.getStrengths());
        boolean hasWeaknesses = isValidAnswer(form.getWeaknesses());

        if (hasStrengths || hasWeaknesses) {
            String llmInput = buildFormAnalysisInput(form);
            log.info("buildFromForm: analyzing text fields for student={}", studentId);
            try {
                ProfileExtractionResult result = extractionAgent.extract(
                    llmInput, profile, completedDims);
                // 只对 knowledge 和 error 维度使用 LLM 分析结果
                DimensionValue kv = result.dimensions().get("knowledge");
                if (kv != null && kv.value() != null && !kv.value().isBlank()) {
                    profile.setKnowledgeBaseLevel(ProfileValueNormalizer.normalizeKnowledgeBase(kv.value()));
                    profile.setKnowledgeBaseConfidence(kv.confidence());
                    if (kv.confidence().compareTo(new BigDecimal("0.50")) >= 0) {
                        completedDims.add("knowledge");
                    }
                }
                DimensionValue ev = result.dimensions().get("error");
                if (ev != null && ev.tags() != null && !ev.tags().isEmpty()) {
                    profile.setErrorPatternTags(new ArrayList<>(ev.tags()));
                    profile.setErrorPatternConfidence(ev.confidence());
                    if (ev.confidence().compareTo(new BigDecimal("0.50")) >= 0) {
                        completedDims.add("error");
                    }
                }
            } catch (Exception e) {
                log.warn("buildFromForm: LLM analysis failed for text fields, using defaults: {}", e.getMessage());
                // LLM 失败时用简单规则推断
                if (hasStrengths) {
                    profile.setKnowledgeBaseLevel("中等");
                    profile.setKnowledgeBaseConfidence(new BigDecimal("0.50"));
                    completedDims.add("knowledge");
                }
            }
        }

        // 3. 如果没有 strength/weakness 信息，用简单规则
        if (!completedDims.contains("knowledge") && hasStrengths) {
            profile.setKnowledgeBaseLevel("中等");
            profile.setKnowledgeBaseConfidence(new BigDecimal("0.40"));
            completedDims.add("knowledge");
        }
        if (!completedDims.contains("error") && hasWeaknesses) {
            profile.setErrorPatternTags(List.of("待诊断"));
            profile.setErrorPatternConfidence(new BigDecimal("0.30"));
            completedDims.add("error");
        }

        // 4. 最终归一化 + 持久化（独立事务，失败会抛出异常供Controller返回给前端）
        sanitizeProfileForDb(profile);
        profileService.updateProfile(studentId, profile);
        log.info("buildFromForm: profile saved for student={}, completedDims={}",
            studentId, completedDims);

        // 5. 生成反馈消息
        String message = buildFormResultMessage(completedDims);
        return new ChatResponse(message, completedDims.size() >= 4, completedDims);
    }

    private boolean isValidAnswer(String value) {
        if (value == null || value.isBlank()) return false;
        String trimmed = value.trim();
        return !trimmed.equals("不确定") && !trimmed.equals("不知道")
            && !trimmed.equals("不清楚") && !trimmed.equals("暂无")
            && !trimmed.equals("无");
    }


    private String buildFormAnalysisInput(ProfileBuildFromFormRequest form) {
        StringBuilder sb = new StringBuilder();
        sb.append("学生填写了学习画像表单：\n\n");
        if (isValidAnswer(form.getMajorAndGrade())) {
            sb.append("专业/年级: ").append(form.getMajorAndGrade()).append("\n");
        }
        if (isValidAnswer(form.getStrengths())) {
            sb.append("学得较好的知识点: ").append(form.getStrengths()).append("\n");
        }
        if (isValidAnswer(form.getWeaknesses())) {
            sb.append("薄弱知识点: ").append(form.getWeaknesses()).append("\n");
        }
        if (isValidAnswer(form.getLearningStyle())) {
            sb.append("学习风格: ").append(form.getLearningStyle()).append("\n");
        }
        if (isValidAnswer(form.getLearningPace())) {
            sb.append("学习节奏: ").append(form.getLearningPace()).append("\n");
        }
        if (isValidAnswer(form.getLearningGoal())) {
            sb.append("学习目标: ").append(form.getLearningGoal()).append("\n");
        }
        sb.append("\n请根据以上信息，分析学生的knowledge（知识基础水平）和error（易错偏好标签）。");
        return sb.toString();
    }

    private String buildFormResultMessage(Set<String> completedDims) {
        StringBuilder sb = new StringBuilder();
        int count = completedDims.size();
        if (count >= 5) {
            sb.append("🎉 太棒了！你的学习画像已经全面构建完成！\n\n");
        } else if (count >= 3) {
            sb.append("✅ 画像已初步构建完成（").append(count).append("/6 个维度）。\n\n");
        } else {
            sb.append("📝 已记录你的基本信息（").append(count).append("/6 个维度）。\n\n");
        }

        sb.append("已识别的维度：\n");
        for (ProfileExtractionAgent.DimensionDef dim : ProfileExtractionAgent.DIMENSIONS) {
            if (completedDims.contains(dim.key())) {
                sb.append("  ✅ ").append(dim.icon()).append(" ").append(dim.label()).append("\n");
            } else {
                sb.append("  ⏳ ").append(dim.icon()).append(" ").append(dim.label()).append(" — 待后续学习完善\n");
            }
        }

        sb.append("\n你可以在个人中心查看完整画像，也可以随时回来更新。");
        return sb.toString();
    }

    /**
     * 对话响应。
     */
    public record ChatResponse(String systemMessage, boolean isComplete, Set<String> completedDimensions) {}

    /**
     * 归一化画像字段：将 LLM 可能输出的英文值转为中文。
     * DB 无 CHECK 约束，varchar(32) 接受任意值。
     */
    private void sanitizeProfileForDb(StudentProfile p) {
        p.setKnowledgeBaseLevel(ProfileValueNormalizer.normalizeKnowledgeBase(p.getKnowledgeBaseLevel()));
        p.setCognitiveStyleType(ProfileValueNormalizer.normalizeCognitiveStyle(p.getCognitiveStyleType()));
        p.setErrorPatternTags(ProfileValueNormalizer.normalizeErrorTags(p.getErrorPatternTags()));
        p.setLearningPaceType(ProfileValueNormalizer.normalizeLearningPace(p.getLearningPaceType()));
        p.setContentPreferenceType(ProfileValueNormalizer.normalizeContentPreference(p.getContentPreferenceType()));
        p.setGoalOrientationType(ProfileValueNormalizer.normalizeGoalOrientation(p.getGoalOrientationType()));

        // confidence: 确保在 [0, 1] 范围内
        p.setKnowledgeBaseConfidence(clampConfidence(p.getKnowledgeBaseConfidence()));
        p.setCognitiveStyleConfidence(clampConfidence(p.getCognitiveStyleConfidence()));
        p.setErrorPatternConfidence(clampConfidence(p.getErrorPatternConfidence()));
        p.setLearningPaceConfidence(clampConfidence(p.getLearningPaceConfidence()));
        p.setGoalOrientationConfidence(clampConfidence(p.getGoalOrientationConfidence()));

        // content_preference_ratio: key 也通过规范化
        Map<String, Double> ratio = p.getContentPreferenceRatio();
        if (ratio != null && !ratio.isEmpty()) {
            Map<String, Double> cleaned = new LinkedHashMap<>();
            for (var entry : ratio.entrySet()) {
                String normalizedKey = ProfileValueNormalizer.normalizeContentPreference(entry.getKey());
                cleaned.put(normalizedKey, entry.getValue());
            }
            p.setContentPreferenceRatio(cleaned);
        }
    }

    private BigDecimal clampConfidence(BigDecimal bd) {
        if (bd == null) return new BigDecimal("0.50");
        if (bd.compareTo(BigDecimal.ZERO) < 0) return BigDecimal.ZERO;
        if (bd.compareTo(BigDecimal.ONE) > 0) return BigDecimal.ONE;
        return bd;
    }
}
