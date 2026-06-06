package org.example.educatorweb.resourcegen.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class XunfeiProvider implements ModelProvider {
    private static final Logger log = LoggerFactory.getLogger(XunfeiProvider.class);
    private final boolean enabled;

    public XunfeiProvider(boolean enabled, String appId, String apiKey, String apiSecret) {
        this.enabled = enabled;
        if (enabled) {
            log.warn("XunfeiProvider initialized but Spark API adapter not yet implemented.");
        }
    }

    @Override
    public String chat(String prompt) {
        throw new UnsupportedOperationException(
            "Xunfei Spark API adapter not yet implemented. Use deepseek for now.");
    }

    @Override
    public String providerName() { return "xunfei"; }

    @Override
    public boolean isEnabled() { return enabled; }
}
