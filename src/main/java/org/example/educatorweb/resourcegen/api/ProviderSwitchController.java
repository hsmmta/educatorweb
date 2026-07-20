package org.example.educatorweb.resourcegen.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.educatorweb.resourcegen.config.ModelRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * API for switching active AI provider and managing API keys at runtime.
 */
@RestController
@RequestMapping("/api/provider")
public class ProviderSwitchController {

    private static final Logger log = LoggerFactory.getLogger(ProviderSwitchController.class);
    private static final Path KEYS_FILE = Path.of("data/provider_keys.json");
    private final ModelRegistry modelRegistry;
    private final ObjectMapper mapper = new ObjectMapper();

    public ProviderSwitchController(ModelRegistry modelRegistry) {
        this.modelRegistry = modelRegistry;
    }

    /** Get current provider, available options, and masked keys */
    @GetMapping("/status")
    public Map<String, Object> getStatus() {
        Map<String, String> savedKeys = loadKeys();
        Map<String, Object> providers = new LinkedHashMap<>();
        for (var entry : modelRegistry.getAvailableProviders().entrySet()) {
            String name = entry.getKey();
            boolean enabled = entry.getValue();
            String masked = maskKey(savedKeys.getOrDefault(name, ""));
            providers.put(name, Map.of("enabled", enabled, "maskedKey", masked));
        }
        return Map.of("current", modelRegistry.getActiveProviderName(), "providers", providers);
    }

    /** Get detailed keys info (masked) */
    @GetMapping("/keys")
    public Map<String, Object> getKeys() {
        Map<String, String> saved = loadKeys();
        Map<String, Object> result = new LinkedHashMap<>();
        for (String name : List.of("deepseek", "xunfei")) {
            String key = saved.getOrDefault(name, "");
            result.put(name, Map.of("configured", !key.isBlank(), "masked", maskKey(key)));
        }
        return result;
    }

    /** Save API key for a provider */
    @PostMapping("/keys")
    public Map<String, Object> saveKey(@RequestBody Map<String, String> body) {
        String provider = body.get("provider");
        String key = body.get("key");
        if (provider == null || provider.isBlank()) throw new IllegalArgumentException("provider required");

        Map<String, String> keys = loadKeys();
        if (key != null && !key.isBlank()) {
            keys.put(provider, key);
        } else {
            keys.remove(provider);
        }
        saveKeys(keys);
        log.info("ProviderSwitch: saved key for {}", provider);
        return Map.of("success", true, "provider", provider,
            "masked", maskKey(keys.getOrDefault(provider, "")));
    }

    /** Switch to a different provider */
    @PostMapping("/switch")
    public Map<String, Object> switchProvider(@RequestBody Map<String, String> body) {
        String target = body.get("provider");
        if (target == null || target.isBlank()) throw new IllegalArgumentException("provider required");
        try {
            modelRegistry.switchProvider(target);
            log.info("ProviderSwitch: switched to {}", target);
            return Map.of("success", true, "current", target);
        } catch (Exception e) {
            return Map.of("success", false, "error", e.getMessage(),
                "current", modelRegistry.getActiveProviderName());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> loadKeys() {
        try {
            if (Files.exists(KEYS_FILE)) {
                return mapper.readValue(KEYS_FILE.toFile(), LinkedHashMap.class);
            }
        } catch (Exception e) { log.warn("ProviderSwitch: cannot load keys: {}", e.getMessage()); }
        return new LinkedHashMap<>();
    }

    private void saveKeys(Map<String, String> keys) {
        try {
            Files.createDirectories(KEYS_FILE.getParent());
            mapper.writeValue(KEYS_FILE.toFile(), keys);
        } catch (Exception e) { log.error("ProviderSwitch: cannot save keys: {}", e.getMessage()); }
    }

    private String maskKey(String key) {
        if (key == null || key.length() <= 8) return key == null || key.isBlank() ? "" : "***";
        return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
    }
}
