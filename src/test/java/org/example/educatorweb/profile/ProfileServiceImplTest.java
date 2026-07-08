package org.example.educatorweb.profile;

import org.example.educatorweb.profile.impl.ProfileServiceImpl;
import org.example.educatorweb.profile.model.StudentProfile;
import org.example.educatorweb.profile.repository.StudentProfileRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProfileServiceImplTest {

    @Mock
    private StudentProfileRepository profileRepo;

    @InjectMocks
    private ProfileServiceImpl profileService;

    // ---- Test 1: profile exists → returned as-is ----

    @Test
    void shouldReturnProfileWhenFound() {
        StudentProfile profile = createTestProfile("student-1");
        when(profileRepo.findById("student-1")).thenReturn(Optional.of(profile));

        StudentProfile result = profileService.getProfile("student-1");

        assertThat(result).isNotNull();
        assertThat(result.getStudentId()).isEqualTo("student-1");
        verify(profileRepo).findById("student-1");
    }

    // ---- Test 2: profile missing → null (graceful) ----

    @Test
    void shouldReturnNullWhenProfileNotFound() {
        when(profileRepo.findById("student-1")).thenReturn(Optional.empty());

        StudentProfile result = profileService.getProfile("student-1");

        assertThat(result).isNull();
        verify(profileRepo).findById("student-1");
    }

    // ---- Test 3: existing profile → only non-null fields overwritten ----

    @Test
    void shouldUpdateExistingProfileFields() {
        StudentProfile existing = createTestProfile("student-1");
        when(profileRepo.findById("student-1")).thenReturn(Optional.of(existing));

        // Partial update: only knowledgeBaseLevel is supplied.
        StudentProfile partial = new StudentProfile();
        partial.setKnowledgeBaseLevel("advanced");
        // errorPatternTags is initialized to an empty list on the entity, so null it
        // out to confirm the service leaves the existing tags untouched.
        partial.setErrorPatternTags(null);

        profileService.updateProfile("student-1", partial);

        ArgumentCaptor<StudentProfile> captor = ArgumentCaptor.forClass(StudentProfile.class);
        verify(profileRepo).save(captor.capture());
        StudentProfile saved = captor.getValue();

        // The persisted entity is the pre-existing one, mutated in place.
        assertThat(saved).isSameAs(existing);

        // Only the supplied field changed.
        assertThat(saved.getKnowledgeBaseLevel()).isEqualTo("advanced");

        // Every other field is left as it was.
        assertThat(saved.getKnowledgeBaseConfidence()).isEqualByComparingTo("0.80");
        assertThat(saved.getCognitiveStyleType()).isEqualTo("visual");
        assertThat(saved.getLearningPaceType()).isEqualTo("normal");
        assertThat(saved.getContentPreferenceType()).isEqualTo("mixed");
        assertThat(saved.getGoalOrientationType()).isEqualTo("exam");
        assertThat(saved.getErrorPatternTags()).containsExactly("careless");
    }

    // ---- Test 4: no existing profile → the supplied entity is persisted ----

    @Test
    void shouldCreateNewProfileWhenNotExisting() {
        when(profileRepo.findById("student-1")).thenReturn(Optional.empty());

        StudentProfile newProfile = createTestProfile("student-1");

        profileService.updateProfile("student-1", newProfile);

        ArgumentCaptor<StudentProfile> captor = ArgumentCaptor.forClass(StudentProfile.class);
        verify(profileRepo).save(captor.capture());
        StudentProfile saved = captor.getValue();

        assertThat(saved).isSameAs(newProfile);
        assertThat(saved.getStudentId()).isEqualTo("student-1");
        assertThat(saved.getKnowledgeBaseLevel()).isEqualTo("beginner");
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
    }

    // ---- Helper: a fully populated profile with default values ----

    private StudentProfile createTestProfile(String id) {
        StudentProfile p = new StudentProfile();
        p.setStudentId(id);
        p.setKnowledgeBaseLevel("beginner");
        p.setKnowledgeBaseConfidence(new BigDecimal("0.80"));
        p.setCognitiveStyleType("visual");
        p.setCognitiveStyleConfidence(new BigDecimal("0.75"));
        p.setErrorPatternTags(new ArrayList<>(List.of("careless")));
        p.setErrorPatternConfidence(new BigDecimal("0.60"));
        p.setLearningPaceType("normal");
        p.setLearningPaceConfidence(new BigDecimal("0.70"));
        p.setContentPreferenceType("mixed");
        p.setContentPreferenceRatio(new HashMap<>(Map.of("video", 0.5, "document", 0.5)));
        p.setGoalOrientationType("exam");
        p.setGoalOrientationConfidence(new BigDecimal("0.85"));
        p.setCreatedAt(LocalDateTime.now());
        p.setUpdatedAt(LocalDateTime.now());
        return p;
    }
}
