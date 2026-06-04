package org.example.educatorweb.resourcegen.agents.generators;

import org.example.educatorweb.common.model.ResourceType;
import org.example.educatorweb.resourcegen.orchestration.AgentNode;

public interface Generator extends AgentNode {
    ResourceType supportedType();
}
