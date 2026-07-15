package org.example.educatorweb.resourcegen.infrastructure;

import reactor.core.publisher.Flux;

public interface ModelProvider {
    /** Blocking call — returns the full response. */
    String chat(String prompt);

    /** Stream the response token-by-token (SSE-compatible). */
    default Flux<String> stream(String prompt) {
        // Default fallback: emit the full response as a single token
        String result = chat(prompt);
        if (result != null && !result.isEmpty()) {
            return Flux.just(result);
        }
        return Flux.empty();
    }

    String providerName();
    default boolean isEnabled() { return true; }
}
