package org.example.educatorweb.learningpath;

import org.example.educatorweb.learningpath.ResourceRecommendService.RecommendationResult;
import org.example.educatorweb.learningpath.model.LearningPath;
import org.example.educatorweb.learningpath.model.PathNode;
import org.example.educatorweb.learningpath.model.PathNode.PathNodeStatus;
import org.example.educatorweb.learningpath.model.RecommendedResource;
import org.example.educatorweb.profile.ProfileService;
import org.example.educatorweb.profile.model.StudentProfile;
import org.example.educatorweb.profile.repository.StudentKnowledgeProficiencyRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResourceRecommendServiceTest {

    @Mock
    private ProfileService profileService;

    @Mock
    private LearningPathService pathService;

    @Mock
    private StudentKnowledgeProficiencyRepository kpProficiencyRepo;

    @InjectMocks
    private ResourceRecommendService resourceRecommendService;

    private static StudentProfile profileWithPreference(String contentPreferenceType) {
        StudentProfile p = new StudentProfile();
        p.setContentPreferenceType(contentPreferenceType);
        p.setCognitiveStyleType("分析型");
        return p;
    }

    // ---- Test 1: recommendByTopic returns base resources plus profile-based VIDEO ----

    @Test
    void shouldRecommendResourcesByTopicLabel() {
        when(profileService.getProfile("student-1"))
            .thenReturn(profileWithPreference("video"));

        List<RecommendedResource> resources =
            resourceRecommendService.recommendByTopic("student-1", "支持向量机", "some context");

        // Base three (DOC, QUIZ, HTML) plus the VIDEO recommendation for the "video" preference
        assertThat(resources).hasSizeGreaterThanOrEqualTo(4);
        assertThat(resources)
            .extracting(RecommendedResource::getResourceType)
            .contains("DOC", "QUIZ", "HTML", "VIDEO");
        assertThat(resources)
            .anyMatch(r -> "VIDEO".equals(r.getResourceType()));
    }

    // ---- Test 2: getDailyRecommendations combines profile, weakness and progress ----

    @Test
    void shouldReturnDailyRecommendations() {
        StudentProfile profile = profileWithPreference("视频优先");
        when(profileService.getProfile("student-1")).thenReturn(profile);

        PathNode currentNode = new PathNode(
            "kp-1", "支持向量机", "SVM 核心思想", 3, "机器学习", 0);
        currentNode.setStatus(PathNodeStatus.CURRENT);
        currentNode.setRecommendedResources(List.of(
            new RecommendedResource("支持向量机 课程文档", "DOC", "来自路径推荐", 9)));

        LearningPath path = new LearningPath();
        path.setPathId("path-1");
        path.setStudentId("student-1");
        path.setTargetKnowledgePoint("支持向量机");
        path.setNodes(List.of(currentNode));

        when(pathService.planPath("student-1", "支持向量机")).thenReturn(path);

        RecommendationResult result =
            resourceRecommendService.getDailyRecommendations("student-1", "支持向量机");

        assertThat(result).isNotNull();
        assertThat(result.allRecommendations()).isNotEmpty();
        assertThat(result.learningPath()).isSameAs(path);
    }

    // ---- Test 3: null profile — base three resources only, no exceptions ----

    @Test
    void shouldHandleNullProfile() {
        when(profileService.getProfile("student-1")).thenReturn(null);

        // Should not throw when the profile is missing
        assertThatCode(() ->
            resourceRecommendService.recommendByTopic("student-1", "支持向量机", "ctx"))
            .doesNotThrowAnyException();

        List<RecommendedResource> resources =
            resourceRecommendService.recommendByTopic("student-1", "支持向量机", "ctx");

        // Only the base three should be present: DOC, QUIZ, HTML — no VIDEO/CODE additions
        assertThat(resources).hasSize(3);
        assertThat(resources)
            .extracting(RecommendedResource::getResourceType)
            .containsExactlyInAnyOrder("DOC", "QUIZ", "HTML");
        assertThat(resources)
            .noneMatch(r -> "VIDEO".equals(r.getResourceType())
                || "CODE".equals(r.getResourceType()));
    }
}
