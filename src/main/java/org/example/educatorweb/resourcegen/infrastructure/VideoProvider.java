package org.example.educatorweb.resourcegen.infrastructure;

public interface VideoProvider {
    byte[] generateVideo(String visualPrompt, int durationSeconds);
    byte[] generateImage(String prompt);
    String providerName();
    default boolean isEnabled() { return true; }
}
