package org.example.educatorweb.resourcegen.orchestration;

import org.example.educatorweb.resourcegen.model.GenerationState;

@FunctionalInterface
public interface Router {
    String route(GenerationState state);
    Router ALWAYS_DONE = state -> "DONE";
    Router ON_ERROR_FALLBACK = state -> state.error() != null ? "FALLBACK" : "DONE";
}
