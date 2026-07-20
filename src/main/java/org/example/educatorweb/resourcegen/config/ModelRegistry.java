package org.example.educatorweb.resourcegen.config;

import org.example.educatorweb.common.model.ResourceType;
import org.example.educatorweb.resourcegen.infrastructure.ModelProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ModelRegistry {
    private static final Logger log = LoggerFactory.getLogger(ModelRegistry.class);

    private final Map<String, ModelProvider> providers = new ConcurrentHashMap<>();
    private volatile String activeProviderName;
    private final ModelProvider visualProvider;

    public ModelRegistry(ModelProvider textProvider, ModelProvider visualProvider,
                         Map<String, ModelProvider> allProviders) {
        this.visualProvider = visualProvider;
        this.providers.putAll(allProviders);
        this.activeProviderName = textProvider.providerName();
        log.info("ModelRegistry: text={}(enabled={}), visual={}(enabled={}), providers={}",
            activeProviderName, textProvider.isEnabled(),
            visualProvider.providerName(), visualProvider.isEnabled(),
            providers.keySet());
    }

    public ModelProvider resolve(ResourceType type) {
        return switch (type) {
            case PPT -> visualProvider.isEnabled() ? visualProvider : getActiveTextProvider();
            default -> getActiveTextProvider();
        };
    }

    /** Get the currently active text provider based on runtime setting. */
    public ModelProvider getActiveTextProvider() {
        ModelProvider p = providers.get(activeProviderName);
        if (p != null && p.isEnabled()) return p;
        // Fallback to any enabled provider
        for (var entry : providers.entrySet()) {
            if (entry.getValue().isEnabled()) {
                log.warn("ModelRegistry: {} is not available, falling back to {}",
                    activeProviderName, entry.getKey());
                return entry.getValue();
            }
        }
        throw new IllegalStateException("No enabled text provider available");
    }

    /** Dynamically switch the active text provider. */
    public void switchProvider(String name) {
        if (providers.containsKey(name)) {
            ModelProvider p = providers.get(name);
            if (p.isEnabled()) {
                activeProviderName = name;
                log.info("ModelRegistry: switched text provider to {}", name);
            } else {
                throw new IllegalArgumentException("Provider " + name + " is not enabled (missing API key?)");
            }
        } else {
            throw new IllegalArgumentException("Unknown provider: " + name + ". Available: " + providers.keySet());
        }
    }

    /** Get current provider name and list of available providers. */
    public String getActiveProviderName() { return activeProviderName; }

    public Map<String, Boolean> getAvailableProviders() {
        Map<String, Boolean> result = new ConcurrentHashMap<>();
        for (var entry : providers.entrySet()) {
            result.put(entry.getKey(), entry.getValue().isEnabled());
        }
        return result;
    }
}
