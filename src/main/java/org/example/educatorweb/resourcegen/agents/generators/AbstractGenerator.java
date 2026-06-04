package org.example.educatorweb.resourcegen.agents.generators;

import org.example.educatorweb.common.model.ResourceType;
import org.example.educatorweb.resourcegen.model.GeneratedResource;
import org.example.educatorweb.resourcegen.model.GenerationState;
import org.example.educatorweb.resourcegen.model.ProgressStage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;

public abstract class AbstractGenerator implements Generator {
    protected final Logger log = LoggerFactory.getLogger(getClass());
    protected final ChatClient chatClient;
    private final ResourceType type;

    protected AbstractGenerator(ChatClient chatClient, ResourceType type) {
        this.chatClient = chatClient;
        this.type = type;
    }

    @Override
    public ResourceType supportedType() {
        return type;
    }

    @Override
    public GenerationState execute(GenerationState state) {
        long start = System.currentTimeMillis();
        try {
            String content = doGenerate(state);
            long elapsed = System.currentTimeMillis() - start;
            log.info("{} generated {} bytes in {} ms", getClass().getSimpleName(),
                content != null ? content.length() : 0, elapsed);
            var resource = GeneratedResource.of(type, state.knowledgePoint(),
                state.blueprint() != null ? state.blueprint().title() : state.knowledgePoint(), content);
            return state.withResult(type, resource).withStage(ProgressStage.GENERATING);
        } catch (Exception e) {
            log.error("Generator {} failed: {}", getClass().getSimpleName(), e.getMessage());
            return state.withResult(type, GeneratedResource.of(type, state.knowledgePoint(),
                "Error", "Generation failed: " + e.getMessage()));
        }
    }

    protected abstract String doGenerate(GenerationState state);

    protected abstract String getFormatHint();
}
