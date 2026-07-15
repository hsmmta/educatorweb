package org.example.educatorweb.learningpath;

import org.springframework.context.ApplicationEvent;

/** Published when the six-dimension profile changes enough to warrant path re-planning. */
public class ProfileUpdatedEvent extends ApplicationEvent {

    private final String studentId;
    private final String newKnowledgeBaseLevel;

    public ProfileUpdatedEvent(Object source, String studentId, String newKnowledgeBaseLevel) {
        super(source);
        this.studentId = studentId;
        this.newKnowledgeBaseLevel = newKnowledgeBaseLevel;
    }

    public String studentId() { return studentId; }
    public String newKnowledgeBaseLevel() { return newKnowledgeBaseLevel; }
}
