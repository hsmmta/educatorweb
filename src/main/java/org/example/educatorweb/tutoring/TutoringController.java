package org.example.educatorweb.tutoring;

import org.example.educatorweb.dto.ResponseResult;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 智能辅导控制器。
 * 提供即时答疑解惑服务，支持多模态答案生成。
 */
@RestController
@RequestMapping("/api/tutoring")
public class TutoringController {

    private final TutoringService tutoringService;

    public TutoringController(TutoringService tutoringService) {
        this.tutoringService = tutoringService;
    }

    /**
     * 提问接口。
     * POST /api/tutoring/ask
     * Body: { "question": "什么是支持向量机？", "studentId": "xxx" }
     */
    @PostMapping("/ask")
    public ResponseResult<Map<String, Object>> ask(@RequestBody AskRequest request) {
        TutoringService.TutoringResponse response = tutoringService.answer(
            request.studentId(), request.question());
        return ResponseResult.success(response.toMap());
    }

    public record AskRequest(String studentId, String question) {
        public AskRequest {
            if (studentId == null || studentId.isBlank()) {
                studentId = "anonymous";
            }
            if (question == null || question.isBlank()) {
                throw new IllegalArgumentException("question is required");
            }
        }
    }
}