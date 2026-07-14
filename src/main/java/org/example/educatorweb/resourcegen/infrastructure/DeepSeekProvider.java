package org.example.educatorweb.resourcegen.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import reactor.core.publisher.Flux;

public class DeepSeekProvider implements ModelProvider {
    private static final Logger log = LoggerFactory.getLogger(DeepSeekProvider.class);
    private final ChatClient chatClient;
    private final boolean enabled;

    public DeepSeekProvider(ChatClient chatClient, boolean enabled) {
        this.chatClient = chatClient;
        this.enabled = enabled;
    }

    @Override
    public String chat(String prompt) {
        log.debug("DeepSeekProvider: sending prompt ({} chars)", prompt.length());
        return chatClient.prompt().user(prompt).call().content();
    }

    @Override
    public Flux<String> chatStream(String prompt) {
        log.debug("DeepSeekProvider: streaming prompt ({} chars)", prompt.length());
        try {
            return chatClient.prompt().user(prompt).stream().content();
        } catch (Exception e) {
            log.error("DeepSeekProvider: stream failed, falling back to blocking: {}", e.getMessage());
            return Flux.just(chat(prompt));
        }
    }

    @Override
    public String providerName() { return "deepseek"; }

    @Override
    public boolean isEnabled() { return enabled; }
}
