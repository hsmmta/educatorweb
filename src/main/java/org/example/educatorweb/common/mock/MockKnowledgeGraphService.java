package org.example.educatorweb.common.mock;

import org.example.educatorweb.knowledgegraph.KnowledgeGraphService;
import org.example.educatorweb.knowledgegraph.model.KnowledgeContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
@Profile("mock")
public class MockKnowledgeGraphService implements KnowledgeGraphService {
    @Override
    public KnowledgeContext queryContext(String knowledgePoint) {
        return new KnowledgeContext(
            List.of("线性回归", "逻辑回归", "最优化基础"),
            List.of("核方法", "SVR", "集成学习"),
            List.of("支持向量", "核函数", "间隔最大化", "KKT条件"),
            3
        );
    }
}
