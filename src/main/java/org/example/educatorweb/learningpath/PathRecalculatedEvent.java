package org.example.educatorweb.learningpath;

import org.springframework.context.ApplicationEvent;

/** Published when a student's learning path is recalculated due to profile changes. */
public class PathRecalculatedEvent extends ApplicationEvent {
    private final String studentId;
    private final int remainingNodes;

    public PathRecalculatedEvent(Object source, String studentId, int remainingNodes) {
        super(source);
        this.studentId = studentId;
        this.remainingNodes = remainingNodes;
    }

    public String studentId() { return studentId; }
    public int remainingNodes() { return remainingNodes; }
}
