package org.example.educatorweb.profile;

import org.example.educatorweb.profile.model.StudentProfile;

public interface ProfileService {
    StudentProfile getProfile(String studentId);
    void updateProfile(String studentId, StudentProfile profile);

    /** Save a learning path JSON to the student's profile. */
    void saveLearningPath(String studentId, String pathJson);

    /** Get the saved learning path JSON from the student's profile. */
    String getSavedLearningPathJson(String studentId);
}
