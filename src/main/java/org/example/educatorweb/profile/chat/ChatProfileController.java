package org.example.educatorweb.profile.chat;

import jakarta.validation.constraints.NotBlank;
import org.example.educatorweb.dto.ResponseResult;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 对话式画像构建控制器。
 * 提供基于自然语言对话的学习画像构建接口。
 */
@RestController
@RequestMapping("/api/profile")
public class ChatProfileController {

    private final ChatProfileService chatProfileService;

    public ChatProfileController(ChatProfileService chatProfileService) {
        this.chatProfileService = chatProfileService;
    }

    /**
     * 开始画像构建对话。返回会话ID和系统的第一个提问。
     */
    @PostMapping("/chat/start")
    public ResponseResult<Map<String, Object>> startChat(@RequestBody StartChatRequest request) {
        ChatSession session = chatProfileService.startSession(
            request.studentId(), request.sessionType());
        String firstQuestion = chatProfileService.getNextQuestion(session.getSessionId());

        return ResponseResult.success(Map.of(
            "sessionId", session.getSessionId(),
            "message", firstQuestion,
            "sessionType", session.getSessionType()
        ));
    }

    /**
     * 发送学生回答，获取系统的下一个提问或完成通知。
     */
    @PostMapping("/chat/message")
    public ResponseResult<Map<String, Object>> sendMessage(@RequestBody ChatMessageRequest request) {
        ChatProfileService.ChatResponse response = chatProfileService.processStudentAnswer(
            request.sessionId(), request.message());

        return ResponseResult.success(Map.of(
            "message", response.systemMessage(),
            "isComplete", response.isComplete(),
            "completedDimensions", response.completedDimensions()
        ));
    }

    /**
     * 获取指定会话的完整对话历史。
     */
    @GetMapping("/chat/history/{sessionId}")
    public ResponseResult<List<ChatMessage>> getHistory(@PathVariable String sessionId) {
        List<ChatMessage> messages = chatProfileService.getSessionMessages(sessionId);
        return ResponseResult.success(messages);
    }

    /**
     * 获取当前活跃的画像构建会话（用于恢复未完成对话）。
     */
    @GetMapping("/chat/active/{studentId}")
    public ResponseResult<Map<String, Object>> getActiveSession(@PathVariable String studentId) {
        return chatProfileService.getLatestSession(studentId, "INITIAL_BUILD")
            .filter(s -> !s.isProfileExtracted())
            .map(session -> {
                List<ChatMessage> messages = chatProfileService.getSessionMessages(session.getSessionId());
                return ResponseResult.<Map<String, Object>>success(Map.of(
                    "sessionId", session.getSessionId(),
                    "sessionType", session.getSessionType(),
                    "messages", messages,
                    "completedDimensions", session.getCompletedDimensions()
                ));
            })
            .orElse(ResponseResult.success(null));
    }

    /**
     * 快速更新：学生直接输入一段描述，AI 一次性提取/更新画像。
     * 适用于画像已有基础、只需微调的场景。
     */
    @PostMapping("/chat/quick-update")
    public ResponseResult<Map<String, Object>> quickUpdate(@RequestBody QuickUpdateRequest request) {
        // 直接触发完整提取：一次 LLM 调用分析全部描述，不走渐进式对话流程
        ChatProfileService.ChatResponse response = chatProfileService.quickExtract(
            request.studentId(), request.description());

        return ResponseResult.success(Map.of(
            "message", response.systemMessage(),
            "isComplete", response.isComplete(),
            "completedDimensions", response.completedDimensions()
        ));
    }

    /**
     * 基于结构化表单构建画像（推荐方式）。
     * 用户填写表单，后端直接映射 + LLM 辅助分析自由文本。
     */
    @PostMapping("/build-from-form")
    public ResponseResult<Map<String, Object>> buildFromForm(@RequestBody ProfileBuildFromFormRequest request) {
        ChatProfileService.ChatResponse response = chatProfileService.buildFromForm(request);

        return ResponseResult.success(Map.of(
            "message", response.systemMessage(),
            "isComplete", response.isComplete(),
            "completedDimensions", response.completedDimensions()
        ));
    }

    // ---- Request DTOs ----

    public record StartChatRequest(
        @NotBlank String studentId,
        String sessionType  // 可选："INITIAL_BUILD"(默认) | "REFRESH"
    ) {
        public StartChatRequest {
            if (sessionType == null || sessionType.isBlank()) {
                sessionType = "INITIAL_BUILD";
            }
        }
    }

    public record ChatMessageRequest(
        @NotBlank String sessionId,
        @NotBlank String message
    ) {}

    public record QuickUpdateRequest(
        @NotBlank String studentId,
        @NotBlank String description
    ) {}
}