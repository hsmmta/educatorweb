package org.example.educatorweb.knowledgegraph;

import org.example.educatorweb.knowledgegraph.model.KnowledgeContext;

public interface KnowledgeGraphService {
    KnowledgeContext queryContext(String knowledgePoint);
}
