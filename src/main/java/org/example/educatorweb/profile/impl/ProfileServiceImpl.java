package org.example.educatorweb.profile.impl;

import org.example.educatorweb.profile.ProfileService;
import org.example.educatorweb.profile.model.StudentKnowledgeProficiency;
import org.example.educatorweb.profile.model.StudentProfile;
import org.example.educatorweb.profile.repository.StudentProfileRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class ProfileServiceImpl implements ProfileService {

    private final StudentProfileRepository profileRepo;

    public ProfileServiceImpl(StudentProfileRepository profileRepo) {
        this.profileRepo = profileRepo;
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
            // 仅更新非 null 字段，支持部分更新
            if (profile.getKnowledgeBaseLevel() != null) {
                entity.setKnowledgeBaseLevel(profile.getKnowledgeBaseLevel());
            }
            if (profile.getKnowledgeBaseConfidence() != null) {
                entity.setKnowledgeBaseConfidence(profile.getKnowledgeBaseConfidence());
            }
            if (profile.getCognitiveStyleType() != null) {
                entity.setCognitiveStyleType(profile.getCognitiveStyleType());
            }
            if (profile.getCognitiveStyleConfidence() != null) {
                entity.setCognitiveStyleConfidence(profile.getCognitiveStyleConfidence());
            }
            if (profile.getErrorPatternTags() != null) {
                entity.setErrorPatternTags(profile.getErrorPatternTags());
            }
            if (profile.getErrorPatternConfidence() != null) {
                entity.setErrorPatternConfidence(profile.getErrorPatternConfidence());
            }
            if (profile.getLearningPaceType() != null) {
                entity.setLearningPaceType(profile.getLearningPaceType());
            }
            if (profile.getLearningPaceConfidence() != null) {
                entity.setLearningPaceConfidence(profile.getLearningPaceConfidence());
            }
            if (profile.getContentPreferenceType() != null) {
                entity.setContentPreferenceType(profile.getContentPreferenceType());
            }
            if (profile.getContentPreferenceRatio() != null) {
                entity.setContentPreferenceRatio(profile.getContentPreferenceRatio());
            }
            if (profile.getGoalOrientationType() != null) {
                entity.setGoalOrientationType(profile.getGoalOrientationType());
            }
            if (profile.getGoalOrientationConfidence() != null) {
                entity.setGoalOrientationConfidence(profile.getGoalOrientationConfidence());
            }
        } else {
            entity = profile;
            entity.setCreatedAt(LocalDateTime.now());
        }
        entity.setUpdatedAt(LocalDateTime.now());

        // 更新知识点熟练度：通过集合操作让 JPA orphanRemoval 自动处理删除和新增
        // 避免使用 bulk delete（绕过一级缓存会导致冲突）
        List<StudentKnowledgeProficiency> details = profile.getKnowledgeDetails();
        if (details != null) {
            // 清理旧数据：orphanRemoval + clear 让 JPA 自动生成 DELETE
            entity.getKnowledgeDetails().clear();
            // 添加新数据
            for (StudentKnowledgeProficiency detail : details) {
                detail.setStudentId(studentId);
                detail.setStudentProfile(entity);
                entity.getKnowledgeDetails().add(detail);
            }
        } else {
            entity.getKnowledgeDetails().clear();
        }

        profileRepo.save(entity);
    }
}
