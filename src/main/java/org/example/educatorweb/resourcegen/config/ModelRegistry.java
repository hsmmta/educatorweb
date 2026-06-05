package org.example.educatorweb.resourcegen.config;

import org.example.educatorweb.common.model.ResourceType;
import org.example.educatorweb.resourcegen.infrastructure.ModelProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ModelRegistry {
    private static final Logger log = LoggerFactory.getLogger(ModelRegistry.class);
    private final ModelProvider textProvider;
    private final ModelProvider visualProvider;

    public ModelRegistry(ModelProvider textProvider, ModelProvider visualProvider) {
        this.textProvider = textProvider;
        this.visualProvider = visualProvider;
        log.info("ModelRegistry: text={}(enabled={}), visual={}(enabled={})",
            textProvider.providerName(), textProvider.isEnabled(),
            visualProvider.providerName(), visualProvider.isEnabled());
    }

    public ModelProvider resolve(ResourceType type) {
        return switch (type) {
            case PPT -> visualProvider.isEnabled() ? visualProvider : textProvider;
            default -> textProvider;
        };
    }
}
