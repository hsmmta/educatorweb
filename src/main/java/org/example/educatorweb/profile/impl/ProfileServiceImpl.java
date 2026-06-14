package org.example.educatorweb.profile.impl;

import org.example.educatorweb.profile.ProfileService;
import org.example.educatorweb.profile.model.StudentKnowledgeProficiency;
import org.example.educatorweb.profile.model.StudentProfile;
import org.example.educatorweb.profile.repository.StudentKnowledgeProficiencyRepository;
import org.example.educatorweb.profile.repository.StudentProfileRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class ProfileServiceImpl implements ProfileService {

    private final StudentProfileRepository profileRepo;
    private final StudentKnowledgeProficiencyRepository knowledgeRepo;

    public ProfileServiceImpl(StudentProfileRepository profileRepo,
                               StudentKnowledgeProficiencyRepository knowledgeRepo) {
        this.profileRepo = profileRepo;
        this.knowledgeRepo = knowledgeRepo;
    }

    @Override
    @Transactional(readOnly = true)
    public StudentProfile getProfile(String studentId) {
        return profileRepo.findById(studentId).orElse(null);
    }

    @Override
    @Transactional
    public void updateProfile(String studentId, StudentProfile profile) {
        // 保证传入对象的 studentId 与路径一致
        profile.setStudentId(studentId);
        // 查询是否已有记录
        Optional<StudentProfile> existingOpt = profileRepo.findById(studentId);
        StudentProfile entity;
        if (existingOpt.isPresent()) {
            entity = existingOpt.get();
            // 更新基础字段
            entity.setKnowledgeBaseLevel(profile.getKnowledgeBaseLevel());
            entity.setKnowledgeBaseConfidence(profile.getKnowledgeBaseConfidence());
            entity.setCognitiveStyleType(profile.getCognitiveStyleType());
            entity.setCognitiveStyleConfidence(profile.getCognitiveStyleConfidence());
            entity.setErrorPatternTags(profile.getErrorPatternTags());
            entity.setErrorPatternConfidence(profile.getErrorPatternConfidence());
            entity.setLearningPaceType(profile.getLearningPaceType());
            entity.setLearningPaceConfidence(profile.getLearningPaceConfidence());
            entity.setContentPreferenceType(profile.getContentPreferenceType());
            entity.setContentPreferenceRatio(profile.getContentPreferenceRatio());
            entity.setGoalOrientationType(profile.getGoalOrientationType());
            entity.setGoalOrientationConfidence(profile.getGoalOrientationConfidence());
        } else {
            entity = profile;
            entity.setCreatedAt(LocalDateTime.now());
        }
        entity.setUpdatedAt(LocalDateTime.now());

        List<StudentKnowledgeProficiency> details = profile.getKnowledgeDetails();
        if (details != null) {
            knowledgeRepo.deleteByStudentId(studentId);
            List<StudentKnowledgeProficiency> newDetails = new ArrayList<>();
            for (StudentKnowledgeProficiency detail : details) {
                detail.setStudentId(studentId);
                detail.setStudentProfile(entity);
                newDetails.add(detail);
            }
            entity.setKnowledgeDetails(newDetails);
        } else {
            entity.setKnowledgeDetails(new ArrayList<>());
        }

        profileRepo.save(entity);
    }
}
