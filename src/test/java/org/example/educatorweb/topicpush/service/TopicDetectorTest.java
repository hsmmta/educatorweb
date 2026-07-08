package org.example.educatorweb.topicpush.service;

import org.example.educatorweb.rag.service.EmbeddingService;
import org.example.educatorweb.resourcegen.infrastructure.ModelProvider;
import org.example.educatorweb.topicpush.model.TopicCache;
import org.example.educatorweb.topicpush.repository.TopicCacheRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TopicDetectorTest {

    private static final String USER_ID = "student-1";
    private static final String CONVERSATION_ID = "conv-1";

    @Mock
    private EmbeddingService embeddingService;

    @Mock
    private TopicCacheRepository cacheRepo;

    @Mock
    private ModelProvider llmProvider;

    private TopicDetector topicDetector;

    @BeforeEach
    void setUp() {
        // Constructor cannot be @InjectMocks because llmProvider is a @Qualifier-selected bean.
        topicDetector = new TopicDetector(embeddingService, cacheRepo, llmProvider);
        // Override the @Value-injected threshold so tests have a deterministic cutoff.
        ReflectionTestUtils.setField(topicDetector, "similarityThreshold", 0.6);
    }

    /** Build a 2048-dim one-hot vector (used to produce orthogonal, low-similarity embeddings). */
    private static float[] unitVector(int hotIndex) {
        float[] v = new float[2048];
        v[hotIndex] = 1f;
        return v;
    }

    /** Build a 2048-dim ramp vector {1,2,3,...}; two identical ramps yield cosine similarity 1.0. */
    private static float[] rampVector() {
        float[] v = new float[2048];
        for (int i = 0; i < v.length; i++) {
            v[i] = i + 1;
        }
        return v;
    }

    // ---- Test 1: Topic shift by low cosine similarity -> previous topic cached ----

    @Test
    void shouldDetectTopicShiftByCosineSimilarity() {
        String firstQuestion = "什么是支持向量机";
        String secondQuestion = "今天天气怎么样";

        // Orthogonal embeddings -> cosine similarity 0.0, below the 0.6 threshold.
        when(embeddingService.embed(firstQuestion)).thenReturn(unitVector(0));
        when(embeddingService.embed(secondQuestion)).thenReturn(unitVector(1));
        when(llmProvider.chat(anyString())).thenReturn("支持向量机");

        // First round: no previous state, then patch in the answer.
        topicDetector.detectAndCache(USER_ID, firstQuestion, CONVERSATION_ID);
        topicDetector.updateAnswer(USER_ID, "SVM 是一种监督学习模型");

        // Second round: similarity is low -> the previous topic must be cached.
        topicDetector.detectAndCache(USER_ID, secondQuestion, CONVERSATION_ID);

        verify(cacheRepo).save(any(TopicCache.class));
    }

    // ---- Test 2: High similarity -> no topic shift -> nothing cached ----

    @Test
    void shouldSkipWhenSimilarityAboveThreshold() {
        String firstQuestion = "什么是支持向量机";
        String secondQuestion = "支持向量机的核函数是什么";

        // Identical ramp embeddings -> cosine similarity 1.0, above the 0.6 threshold.
        when(embeddingService.embed(firstQuestion)).thenReturn(rampVector());
        when(embeddingService.embed(secondQuestion)).thenReturn(rampVector());

        // First round establishes state, updateAnswer makes the previous Q&A cacheable.
        topicDetector.detectAndCache(USER_ID, firstQuestion, CONVERSATION_ID);
        topicDetector.updateAnswer(USER_ID, "SVM 是一种监督学习模型");

        // Second round: similarity is high -> no shift -> no cache.
        topicDetector.detectAndCache(USER_ID, secondQuestion, CONVERSATION_ID);

        verify(cacheRepo, never()).save(any(TopicCache.class));
    }

    // ---- Test 3: First message, no previous state -> nothing cached ----

    @Test
    void shouldHandleFirstMessageWithoutPreviousState() {
        String question = "什么是支持向量机";
        when(embeddingService.embed(question)).thenReturn(unitVector(0));

        topicDetector.detectAndCache(USER_ID, question, CONVERSATION_ID);

        verify(cacheRepo, never()).save(any(TopicCache.class));
    }

    // ---- Test 4: LLM labeling fails -> fallback label (first 15 chars of previous question) ----

    @Test
    void shouldFallbackLabelWhenLLMFails() {
        String firstQuestion = "What is the fundamental principle behind support vector machines?";
        String secondQuestion = "How is today's weather in Beijing?";

        // Orthogonal embeddings -> low similarity triggers caching of the previous topic.
        when(embeddingService.embed(firstQuestion)).thenReturn(unitVector(0));
        when(embeddingService.embed(secondQuestion)).thenReturn(unitVector(1));
        // LLM labeling blows up -> detector must fall back to a truncated question label.
        when(llmProvider.chat(anyString())).thenThrow(new RuntimeException("LLM unavailable"));

        topicDetector.detectAndCache(USER_ID, firstQuestion, CONVERSATION_ID);
        topicDetector.updateAnswer(USER_ID, "SVM finds a maximal-margin hyperplane");

        topicDetector.detectAndCache(USER_ID, secondQuestion, CONVERSATION_ID);

        ArgumentCaptor<TopicCache> captor = ArgumentCaptor.forClass(TopicCache.class);
        verify(cacheRepo).save(captor.capture());

        String expectedLabel = firstQuestion.substring(0, 15) + "...";
        assertThat(captor.getValue().getTopicLabel()).isEqualTo(expectedLabel);
    }
}
