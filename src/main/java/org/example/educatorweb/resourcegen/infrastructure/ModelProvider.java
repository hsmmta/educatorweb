package org.example.educatorweb.resourcegen.infrastructure;

import reactor.core.publisher.Flux;

public interface ModelProvider {
    String chat(String prompt);
    String providerName();
    default boolean isEnabled() { return true; }

    /** 流式输出，默认降级为非流式 */
    default Flux<String> chatStream(String prompt) {
        return Flux.just(chat(prompt));
    }
}
