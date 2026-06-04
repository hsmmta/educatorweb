package org.example.educatorweb.resourcegen.agents.generators;

import org.example.educatorweb.common.model.GenerateRequest;
import org.example.educatorweb.common.model.ResourceType;
import org.example.educatorweb.knowledgegraph.model.KnowledgeContext;
import org.example.educatorweb.profile.model.StudentProfile;
import org.example.educatorweb.rag.model.DocumentSnippet;
import org.example.educatorweb.resourcegen.model.GeneratedResource;
import org.example.educatorweb.resourcegen.model.GenerationState;
import org.example.educatorweb.resourcegen.model.ResourceBlueprint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocGeneratorTest {

    @Mock
    private ChatClient chatClient;

    @Mock
    private ChatClient.ChatClientRequestSpec requestSpec;

    @Mock
    private ChatClient.CallResponseSpec responseSpec;

    @InjectMocks
    private DocGenerator docGenerator;

    private static final String MOCK_MARKDOWN = """
        # 支持向量机(SVM) 教学文档

        ## 1. 核心思想

        支持向量机（Support Vector Machine, SVM）是一种强大的**监督学习**算法，
        其核心思想是找到一个最优超平面来分隔不同类别的数据点。

        ### 1.1 最大间隔原理

        SVM 的目标是最大化分类间隔（margin），即数据点到决策边界的最小距离：

        $$\\max_{w,b} \\frac{2}{\\|w\\|}$$

        ## 2. 数学推导

        对于线性可分的情况，SVM 的优化问题可以表示为：

        $$\\min_{w,b} \\frac{1}{2}\\|w\\|^2 \\quad \\text{s.t.} \\quad y_i(w^Tx_i + b) \\geq 1$$

        ### 2.1 拉格朗日对偶性

        通过引入拉格朗日乘子 $\\alpha_i \\geq 0$，我们可以得到对偶问题。

        ```python
        from sklearn import svm

        # 创建 SVM 分类器
        clf = svm.SVC(kernel='rbf', C=1.0)
        clf.fit(X_train, y_train)

        # 预测
        predictions = clf.predict(X_test)
        ```

        ## 3. 核方法

        | 核函数 | 表达式 | 适用场景 |
        |--------|--------|----------|
        | 线性核 | $K(x,z) = x^T z$ | 线性可分数据 |
        | 多项式核 | $K(x,z) = (x^T z + c)^d$ | 非线性数据 |
        | RBF 核 | $K(x,z) = \\exp(-\\gamma\\|x-z\\|^2)$ | 通用场景 |

        ## 4. 实践要点

        - **参数 C 的选择**：C 越大，对误分类的惩罚越大
        - **核函数选择**：先尝试线性核，再考虑 RBF 核
        - **数据预处理**：特征缩放对 SVM 至关重要

        > **关键要点**：SVM 通过最大间隔原理和核技巧，既能处理线性分类问题，也能处理复杂的非线性分类问题。

        ## 思考题

        1. 为什么 SVM 对特征缩放敏感？
        2. 当数据量很大时，SVM 的计算复杂度是多少？
        """;

    @Test
    void shouldGenerateMarkdownDocument() {
        // Setup mock chain
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn(MOCK_MARKDOWN);

        // Create a GenerationState with blueprint, profile, and context
        var profile = new StudentProfile(
            new StudentProfile.D1_KnowledgeBase("一般", 0.85,
                Map.of("Python", "熟练", "线性代数", "了解")),
            new StudentProfile.D2_CognitiveStyle("直觉型", 0.72),
            new StudentProfile.D3_ErrorPattern(List.of("过拟合概念混淆"), 0.68),
            new StudentProfile.D4_LearningPace("稳扎稳打型", 0.90),
            new StudentProfile.D5_ContentPreference("混合学习",
                Map.of("video", 0.4, "document", 0.35)),
            new StudentProfile.D6_GoalOrientation("求职准备", 0.88)
        );

        var kgContext = new KnowledgeContext(
            List.of("线性回归"), List.of("核方法"),
            List.of("支持向量", "核函数"), 3);

        var snippets = List.of(
            new DocumentSnippet("SVM的核心思想是最大化分类间隔...", "教材-第6章", 0.95)
        );

        var blueprint = new ResourceBlueprint(
            "支持向量机(SVM)从入门到实践",
            "系统讲解SVM原理",
            List.of(
                new ResourceBlueprint.BlueprintSection("核心思想", 1,
                    "最大间隔原理和最优超平面", List.of()),
                new ResourceBlueprint.BlueprintSection("数学推导", 1,
                    "拉格朗日对偶性和KKT条件", List.of()),
                new ResourceBlueprint.BlueprintSection("核方法", 1,
                    "核技巧与常见核函数", List.of())
            ),
            Map.of(),
            List.of(),
            Instant.now()
        );

        GenerateRequest req = new GenerateRequest("student-1", "SVM", List.of(ResourceType.DOC));
        GenerationState state = GenerationState.initial(req);

        state = state.withProfile(profile)
                     .withContext(kgContext, snippets)
                     .withBlueprint(blueprint);

        // Execute
        GenerationState result = docGenerator.execute(state);

        // Verify
        assertThat(result).isNotNull();
        assertThat(result.results()).containsKey(ResourceType.DOC);

        GeneratedResource doc = result.results().get(ResourceType.DOC);
        assertThat(doc).isNotNull();
        assertThat(doc.type()).isEqualTo(ResourceType.DOC);
        assertThat(doc.title()).isEqualTo("支持向量机(SVM)从入门到实践");
        assertThat(doc.knowledgePoint()).isEqualTo("SVM");
        assertThat(doc.content()).isNotNull();
        assertThat(doc.content()).isNotEmpty();

        // Verify Markdown elements in content
        assertThat(doc.content()).contains("# ");
        assertThat(doc.content()).contains("## ");
        assertThat(doc.content()).contains("**");
        assertThat(doc.content()).contains("$$");
        assertThat(doc.content()).contains("```python");
        assertThat(doc.content()).contains("|");
    }

    @Test
    void shouldHandleEmptyLlmResponse() {
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn("");

        GenerateRequest req = new GenerateRequest("student-1", "SVM", List.of(ResourceType.DOC));
        GenerationState state = GenerationState.initial(req);

        GenerationState result = docGenerator.execute(state);

        assertThat(result.results()).containsKey(ResourceType.DOC);
        GeneratedResource doc = result.results().get(ResourceType.DOC);
        assertThat(doc.content()).isNotNull();
        assertThat(doc.content()).isEmpty();
    }

    @Test
    void shouldHandleMissingBlueprintProfileAndContext() {
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn("# Basic Topic\n\nSimple content here.");

        GenerateRequest req = new GenerateRequest("student-1", "Linear Algebra",
            List.of(ResourceType.DOC));
        GenerationState state = GenerationState.initial(req);

        // No profile, no context, no blueprint — all null
        GenerationState result = docGenerator.execute(state);

        assertThat(result.results()).containsKey(ResourceType.DOC);
        GeneratedResource doc = result.results().get(ResourceType.DOC);
        assertThat(doc).isNotNull();
        assertThat(doc.content()).contains("# Basic Topic");
    }

    @Test
    void shouldReturnSupportedType() {
        assertThat(docGenerator.supportedType()).isEqualTo(ResourceType.DOC);
    }
}
