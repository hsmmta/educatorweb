package org.example.educatorweb.profile;

import org.example.educatorweb.profile.model.ProfileAnalysisResult;

/**
 * Service that orchestrates profile analysis using LangChain4j agent
 * to infer learner profile from natural conversation history.
 */
public interface ProfileAnalysisService {

    /**
     * Analyze a student's recent conversations and update their learner profile.
     *
     * @param studentId the student to analyze
     * @return the analysis result including new profile values and reasoning
     */
    ProfileAnalysisResult analyzeAndUpdate(String studentId);
}
