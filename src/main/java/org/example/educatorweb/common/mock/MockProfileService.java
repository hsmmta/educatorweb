package org.example.educatorweb.common.mock;

import org.example.educatorweb.profile.ProfileService;
import org.example.educatorweb.profile.model.StudentProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Map;

@Service
@Profile("mock")
public class MockProfileService implements ProfileService {

    private static final Logger log = LoggerFactory.getLogger(MockProfileService.class);

    @Override
    public StudentProfile getProfile(String studentId) {
        return new StudentProfile(
            new StudentProfile.D1_KnowledgeBase("一般", 0.85,
                Map.of("Python", "熟练", "线性代数", "了解", "概率论", "一般")),
            new StudentProfile.D2_CognitiveStyle("直觉型", 0.72),
            new StudentProfile.D3_ErrorPattern(List.of("过拟合概念混淆", "梯度消失理解错误"), 0.68),
            new StudentProfile.D4_LearningPace("稳扎稳打型", 0.90),
            new StudentProfile.D5_ContentPreference("混合学习",
                Map.of("video", 0.4, "document", 0.35, "code", 0.25)),
            new StudentProfile.D6_GoalOrientation("求职准备", 0.88)
        );
    }

    @Override
    public void updateProfile(String studentId, StudentProfile profile) {
        log.debug("MockProfileService: updateProfile called for studentId={}", studentId);
    }
}
