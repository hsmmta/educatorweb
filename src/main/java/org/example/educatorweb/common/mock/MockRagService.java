package org.example.educatorweb.common.mock;

import org.example.educatorweb.rag.RagService;
import org.example.educatorweb.rag.model.DocumentSnippet;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Map;

@Service
public class MockRagService implements RagService {
    @Override
    public List<DocumentSnippet> retrieve(String userId, String query, int topK) {
        return List.of(
            new DocumentSnippet("SVM的核心思想是找到一个最优超平面，使得不同类别之间的间隔最大化...",
                "机器学习教材-第6章", 0.95),
            new DocumentSnippet("拉格朗日对偶性是解决约束优化问题的重要工具...",
                "最优化方法-第3章", 0.88),
            new DocumentSnippet("核技巧允许SVM在高维空间中隐式地计算内积...",
                "统计学习方法-第7章", 0.82)
        );
    }

    @Override
    public List<Map<String, Object>> listDocuments(String userId) {
        return List.of();
    }
}
