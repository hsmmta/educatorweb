package org.example.educatorweb.resourcegen.infrastructure;

public interface ModelProvider {
    String chat(String prompt);
    String providerName();
    default boolean isEnabled() { return true; }
}
