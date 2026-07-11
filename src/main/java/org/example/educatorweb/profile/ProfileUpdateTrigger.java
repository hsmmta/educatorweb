package org.example.educatorweb.profile;

import org.example.educatorweb.profile.impl.ProfileAnalysisServiceImpl;
import org.example.educatorweb.profile.model.StudentProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * 画像更新触发器。
 * 追踪学生累计对话轮数，满足条件时触发画像分析更新。
 *
 * <p>触发条件（任一满足即触发）：
 * <ul>
 *   <li>累计对话轮数 >= 7</li>
 *   <li>距离上次画像更新超过 3 天</li>
 * </ul>
 *
 * <p>触发后重置轮数计数器，更新 lastProfileUpdateAt 时间戳。
 */
@Service
public class ProfileUpdateTrigger {

    private static final Logger log = LoggerFactory.getLogger(ProfileUpdateTrigger.class);

    /** 触发画像更新的累计对话轮数阈值 */
    private static final int ROUND_THRESHOLD = 7;

    /** 距离上次更新的最小天数 */
    private static final int DAYS_THRESHOLD = 3;

    private final ProfileService profileService;
    private final ProfileAnalysisServiceImpl analysisService;

    public ProfileUpdateTrigger(ProfileService profileService,
                                 ProfileAnalysisServiceImpl analysisService) {
        this.profileService = profileService;
        this.analysisService = analysisService;
    }

    /**
     * 每次用户交互（AI 对话或答题）后调用。
     * 轮数 +1，检查是否满足触发条件。
     *
     * @param studentId 学生ID
     * @return true 如果触发了画像更新
     */
    public boolean onInteraction(String studentId) {
        StudentProfile profile = profileService.getProfile(studentId);
        if (profile == null) {
            log.debug("ProfileUpdateTrigger: no profile for student={}, skipping", studentId);
            return false;
        }

        // 轮数 +1
        int rounds = profile.getTotalConversationRounds() + 1;
        profile.setTotalConversationRounds(rounds);

        LocalDateTime lastUpdate = profile.getLastProfileUpdateAt();
        long daysSinceUpdate = lastUpdate != null
            ? ChronoUnit.DAYS.between(lastUpdate, LocalDateTime.now())
            : Long.MAX_VALUE;

        boolean shouldUpdate = rounds >= ROUND_THRESHOLD || daysSinceUpdate >= DAYS_THRESHOLD;

        log.info("ProfileUpdateTrigger: student={}, rounds={}/{}, daysSinceUpdate={}/{}, shouldUpdate={}",
            studentId, rounds, ROUND_THRESHOLD, daysSinceUpdate, DAYS_THRESHOLD, shouldUpdate);

        if (shouldUpdate) {
            // 重置轮数计数器
            profile.setTotalConversationRounds(0);
            profile.setLastProfileUpdateAt(LocalDateTime.now());
            profileService.updateProfile(studentId, profile);

            // 异步触发画像分析（从 Chroma 对话中重新提取画像）
            try {
                analysisService.analyzeAndUpdate(studentId);
                log.info("ProfileUpdateTrigger: profile analysis completed for student={}", studentId);
            } catch (Exception e) {
                log.error("ProfileUpdateTrigger: profile analysis failed for student={}: {}",
                    studentId, e.getMessage());
            }
            return true;
        } else {
            // 仅持久化轮数
            profileService.updateProfile(studentId, profile);
            return false;
        }
    }

    /**
     * 获取当前轮数状态（用于前端展示）。
     */
    public RoundStatus getStatus(String studentId) {
        StudentProfile profile = profileService.getProfile(studentId);
        if (profile == null) {
            return new RoundStatus(0, ROUND_THRESHOLD, 0, DAYS_THRESHOLD, null);
        }
        LocalDateTime lastUpdate = profile.getLastProfileUpdateAt();
        long daysSince = lastUpdate != null
            ? ChronoUnit.DAYS.between(lastUpdate, LocalDateTime.now())
            : Long.MAX_VALUE;
        return new RoundStatus(
            profile.getTotalConversationRounds(), ROUND_THRESHOLD,
            daysSince, DAYS_THRESHOLD,
            lastUpdate
        );
    }

    public record RoundStatus(int currentRounds, int roundThreshold,
                               long daysSinceUpdate, int daysThreshold,
                               LocalDateTime lastProfileUpdateAt) {}
}
