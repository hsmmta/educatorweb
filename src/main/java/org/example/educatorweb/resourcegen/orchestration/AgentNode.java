package org.example.educatorweb.resourcegen.orchestration;

import org.example.educatorweb.resourcegen.model.GenerationState;

@FunctionalInterface
public interface AgentNode {
    GenerationState execute(GenerationState state);
}
