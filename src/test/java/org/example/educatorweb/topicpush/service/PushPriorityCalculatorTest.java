package org.example.educatorweb.topicpush.service;

import org.example.educatorweb.profile.model.StudentKnowledgeProficiency;
import org.example.educatorweb.profile.repository.StudentKnowledgeProficiencyRepository;
import org.example.educatorweb.topicpush.model.TopicCache;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PushPriorityCalculatorTest {

    @Mock
    private StudentKnowledgeProficiencyRepository kpProficiencyRepo;

    @InjectMocks
    private PushPriorityCalculator calculator;

    // ---- Test 1: Sort by priority score (lower score = higher priority) ----
    @Test
    void shouldSortByPriorityScore() {
        // proficiencyRank = (int)(proficiency * 100), recencyRank = originalIndex + 1
        // score = proficiencyRank * 0.7 + recencyRank * 0.3
        // quadratic (0.9): rank=90, recency=1 → 63.3
        // linear     (0.5): rank=50, recency=2 → 35.6
        // fractions  (0.2): rank=20, recency=3 → 14.9
        LocalDateTime now = LocalDateTime.now();
        TopicCache t1 = new TopicCache("student-1", "quadratic_equations", "QA1", "conv-1", now.minusDays(1));
        TopicCache t2 = new TopicCache("student-1", "linear_equations", "QA2", "conv-2", now.minusDays(3));
        TopicCache t3 = new TopicCache("student-1", "fractions", "QA3", "conv-3", now.minusDays(5));
        List<TopicCache> topics = List.of(t1, t2, t3);

        StudentKnowledgeProficiency kp1 = new StudentKnowledgeProficiency();
        kp1.setConcept("quadratic_equations");
        kp1.setProficiency(new BigDecimal("0.9"));
        StudentKnowledgeProficiency kp2 = new StudentKnowledgeProficiency();
        kp2.setConcept("linear_equations");
        kp2.setProficiency(new BigDecimal("0.5"));
        StudentKnowledgeProficiency kp3 = new StudentKnowledgeProficiency();
        kp3.setConcept("fractions");
        kp3.setProficiency(new BigDecimal("0.2"));
        List<StudentKnowledgeProficiency> details = List.of(kp1, kp2, kp3);

        when(kpProficiencyRepo.findByStudentId("student-1")).thenReturn(details);

        List<PushPriorityCalculator.PrioritizedTopic> result =
            calculator.prioritize(topics, "student-1", false);

        assertThat(result).hasSize(3);
        assertThat(result).extracting(PushPriorityCalculator.PrioritizedTopic::score)
            .isSorted(); // ascending = lower score first = highest priority first
        assertThat(result.get(0).topicLabel()).isEqualTo("fractions");
        assertThat(result.get(1).topicLabel()).isEqualTo("linear_equations");
        assertThat(result.get(2).topicLabel()).isEqualTo("quadratic_equations");
    }

    // ---- Test 2: Prepend weakness topics when scheduled ----
    @Test
    void shouldPrependWeaknessTopicsWhenScheduled() {
        StudentKnowledgeProficiency weak1 = new StudentKnowledgeProficiency();
        weak1.setConcept("fractions");
        weak1.setProficiency(new BigDecimal("0.3"));
        weak1.setLastStudyTime(LocalDateTime.now().minusDays(2));
        StudentKnowledgeProficiency weak2 = new StudentKnowledgeProficiency();
        weak2.setConcept("decimals");
        weak2.setProficiency(new BigDecimal("0.5"));
        weak2.setLastStudyTime(LocalDateTime.now().minusDays(1));
        StudentKnowledgeProficiency strong = new StudentKnowledgeProficiency();
        strong.setConcept("algebra");
        strong.setProficiency(new BigDecimal("0.8"));
        List<StudentKnowledgeProficiency> details = List.of(weak1, weak2, strong);

        when(kpProficiencyRepo.findByStudentId("student-1")).thenReturn(details);

        TopicCache topic = new TopicCache("student-1", "algebra", "QA", "conv-1", LocalDateTime.now());
        List<TopicCache> topics = List.of(topic);

        List<PushPriorityCalculator.PrioritizedTopic> result =
            calculator.prioritize(topics, "student-1", true);

        // 2 weakness topics (proficiency < 0.6) prepended + 1 original
        assertThat(result).hasSize(3);
        // Weakness topics appear first and are synthetic
        assertThat(result.get(0).isSynthetic()).isTrue();
        assertThat(result.get(0).topicLabel()).isEqualTo("fractions"); // weaker first
        assertThat(result.get(0).qaText()).contains("薄弱点");
        assertThat(result.get(1).isSynthetic()).isTrue();
        assertThat(result.get(1).topicLabel()).isEqualTo("decimals");
        assertThat(result.get(1).qaText()).contains("薄弱点");
        // Original topic follows
        assertThat(result.get(2).topicLabel()).isEqualTo("algebra");
    }

    // ---- Test 3: Handle null profile gracefully ----
    @Test
    void shouldHandleNullProfileGracefully() {
        when(kpProficiencyRepo.findByStudentId("student-1")).thenReturn(List.of());

        TopicCache t1 = new TopicCache("student-1", "topic-a", "QA1", "conv-1", LocalDateTime.now().minusDays(2));
        TopicCache t2 = new TopicCache("student-1", "topic-b", "QA2", "conv-2", LocalDateTime.now().minusDays(1));
        List<TopicCache> topics = List.of(t1, t2);

        // When & Then: no exception, returns all original topics
        List<PushPriorityCalculator.PrioritizedTopic> result =
            calculator.prioritize(topics, "student-1", true);

        assertThat(result).hasSize(2);
        assertThat(result).extracting(PushPriorityCalculator.PrioritizedTopic::topicLabel)
            .containsExactly("topic-a", "topic-b");
    }

    // ---- Test 4: Handle empty knowledge details ----
    @Test
    void shouldHandleEmptyKnowledgeDetails() {
        when(kpProficiencyRepo.findByStudentId("student-1")).thenReturn(List.of());

        TopicCache t1 = new TopicCache("student-1", "topic-a", "normal QA", "conv-1", LocalDateTime.now().minusDays(2));
        TopicCache t2 = new TopicCache("student-1", "topic-b", "another QA", "conv-2", LocalDateTime.now().minusDays(1));
        List<TopicCache> topics = List.of(t1, t2);

        List<PushPriorityCalculator.PrioritizedTopic> result =
            calculator.prioritize(topics, "student-1", true);

        // No weakness topics prepended — size equals input count
        assertThat(result).hasSize(2);
        // None of the results are weakness-generate synthetic topics
        assertThat(result).extracting(PushPriorityCalculator.PrioritizedTopic::qaText)
            .noneMatch(qa -> qa.contains("薄弱点"));
        // Order preserved by score (both get default proficiency score=50)
        assertThat(result).extracting(PushPriorityCalculator.PrioritizedTopic::topicLabel)
            .containsExactly("topic-a", "topic-b");
    }
}
