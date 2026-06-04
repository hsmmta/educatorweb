package org.example.educatorweb.profile;

import org.example.educatorweb.profile.model.StudentProfile;

public interface ProfileService {
    StudentProfile getProfile(String studentId);
}
