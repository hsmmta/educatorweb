package org.example.educatorweb.common.mock;

import org.example.educatorweb.profile.ProfileService;
import org.example.educatorweb.profile.model.StudentProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Service
@Profile("mock")
public class MockProfileService implements ProfileService {

    private static final Logger log = LoggerFactory.getLogger(MockProfileService.class);

    @Override
    public StudentProfile getProfile(String studentId) {
        StudentProfile profile = new StudentProfile();
        profile.setKnowledgeBaseLevel("一般");
        profile.setKnowledgeBaseConfidence(new BigDecimal("0.85"));
        profile.setCognitiveStyleType("直觉型");
        profile.setCognitiveStyleConfidence(new BigDecimal("0.72"));
        profile.setErrorPatternTags(List.of("过拟合概念混淆", "梯度消失理解错误"));
        profile.setErrorPatternConfidence(new BigDecimal("0.68"));
        profile.setLearningPaceType("稳扎稳打型");
        profile.setLearningPaceConfidence(new BigDecimal("0.90"));
        profile.setContentPreferenceType("混合学习");
        profile.setContentPreferenceRatio(Map.of("video", 0.4, "document", 0.35, "code", 0.25));
        profile.setGoalOrientationType("求职准备");
        profile.setGoalOrientationConfidence(new BigDecimal("0.88"));
        return profile;
    }

    @Override
    public void updateProfile(String studentId, StudentProfile profile) {
        log.debug("MockProfileService: updateProfile called for studentId={}", studentId);
    }
}
