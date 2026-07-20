package org.example.educatorweb.profile.chat;

import java.util.List;

/**
 * 表单式画像构建请求。
 * 用户填写结构化表单，后端直接映射 + LLM 辅助分析。
 */
public class ProfileBuildFromFormRequest {

    private String studentId;

    /** 学得较好的知识点（自由文本） */
    private String strengths;

    /** 相对薄弱的知识点（自由文本） */
    private String weaknesses;

    /** 偏好的学习资料类型（多选） */
    private List<String> preferredResourceTypes;

    /** 学习风格：视觉型/言语型/直觉型/分析型/不确定 */
    private String learningStyle;

    /** 学习节奏：稳扎稳打/快速推进/跳跃学习/不确定 */
    private String learningPace;

    /** 学习目标：求职准备/学术深造/兴趣探索/考证通关/课程考试/不确定 */
    private String learningGoal;

    /** 专业/年级（可选自由文本） */
    private String majorAndGrade;

    public String getStudentId() { return studentId; }
    public void setStudentId(String studentId) { this.studentId = studentId; }

    public String getStrengths() { return strengths; }
    public void setStrengths(String strengths) { this.strengths = strengths; }

    public String getWeaknesses() { return weaknesses; }
    public void setWeaknesses(String weaknesses) { this.weaknesses = weaknesses; }

    public List<String> getPreferredResourceTypes() { return preferredResourceTypes; }
    public void setPreferredResourceTypes(List<String> preferredResourceTypes) { this.preferredResourceTypes = preferredResourceTypes; }

    public String getLearningStyle() { return learningStyle; }
    public void setLearningStyle(String learningStyle) { this.learningStyle = learningStyle; }

    public String getLearningPace() { return learningPace; }
    public void setLearningPace(String learningPace) { this.learningPace = learningPace; }

    public String getLearningGoal() { return learningGoal; }
    public void setLearningGoal(String learningGoal) { this.learningGoal = learningGoal; }

    public String getMajorAndGrade() { return majorAndGrade; }
    public void setMajorAndGrade(String majorAndGrade) { this.majorAndGrade = majorAndGrade; }
}
