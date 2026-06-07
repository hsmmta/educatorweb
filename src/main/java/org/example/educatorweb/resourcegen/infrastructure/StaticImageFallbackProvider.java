package org.example.educatorweb.resourcegen.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Always-on fallback provider. When Seedance/Veo/CogVideoX are unavailable,
 * this provider generates a simple static frame from text.
 * The VideoAssembler will handle these as text-only frames.
 */
public class StaticImageFallbackProvider implements VideoProvider {
    private static final Logger log = LoggerFactory.getLogger(StaticImageFallbackProvider.class);
    private final ModelProvider imageModel;

    public StaticImageFallbackProvider(ModelProvider imageModel) {
        this.imageModel = imageModel;
        log.info("StaticImageFallbackProvider initialized as safety net");
    }

    @Override
    public byte[] generateVideo(String visualPrompt, int durationSeconds) {
        log.info("StaticImageFallback: using text model for visual prompt ({} chars)", visualPrompt.length());
        // Generate image description via text model
        String enhancedPrompt = imageModel.chat(
            "Create a detailed image description for: " + visualPrompt);
        // For now, return empty — VideoAssembler handles text-only frames
        throw new UnsupportedOperationException(
            "Static video frame generation not yet implemented — using pure-text fallback");
    }

    @Override
    public byte[] generateImage(String prompt) {
        String enhanced = imageModel.chat("Generate an image description for: " + prompt);
        throw new UnsupportedOperationException("Image generation not yet implemented");
    }

    @Override public String providerName() { return "static-image-fallback"; }
    @Override public boolean isEnabled() { return true; }
}
