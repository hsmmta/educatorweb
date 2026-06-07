# Multi-Model Provider Architecture — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the single shared ChatClient with a multi-provider ModelRegistry where text tasks route to DeepSeek and visual tasks (PPT) route to a configurable visual provider — all driven by YAML configuration with enable/disable switches.

**Architecture:** Introduce a `ModelProvider` interface with one method `chat(prompt)`. Build `DeepSeekProvider` (wrapping existing ChatClient) and `OpenAiCompatibleProvider` (covering OpenAI/OpenRouter/SiliconFlow with a single implementation). Create `ModelRegistry` to route by `ResourceType`. Refactor `AbstractGenerator` to use `ModelRegistry` instead of `ChatClient`. Split `VideoGenerator` into two-phase: DeepSeek → LectureScript → Visual Provider → PPTX, with graceful fallback to Apache POI V1 when no visual provider is enabled.

**Tech Stack:** Spring Boot 3.4.3, Spring AI 1.0.0-M6, Java 21

**Design Spec:** `docs/superpowers/specs/2026-06-05-multi-model-provider-design.md`

---

### Task 1: Phase A — Core Interfaces and Data Models

**Files:**
- Create: `src/main/java/org/example/educatorweb/resourcegen/infrastructure/ModelProvider.java`
- Create: `src/main/java/org/example/educatorweb/resourcegen/config/ModelRoutingProperties.java`
- Create: `src/main/java/org/example/educatorweb/resourcegen/model/LectureScript.java`

- [ ] **Step 1: Create ModelProvider interface**

```java
package org.example.educatorweb.resourcegen.infrastructure;

public interface ModelProvider {
    String chat(String prompt);
    String providerName();
    default boolean isEnabled() { return true; }
}
```

- [ ] **Step 2: Create ModelRoutingProperties for YAML binding**

```java
package org.example.educatorweb.resourcegen.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.Map;

@ConfigurationProperties(prefix = "model-routing")
public record ModelRoutingProperties(
    ModelConfig text,
    ModelConfig visual,
    Map<String, ProviderConfig> providers
) {
    public record ModelConfig(String provider, String model, double temperature) {
        public ModelConfig {
            if (temperature == 0) temperature = 0.7;
        }
    }

    public record ProviderConfig(
        boolean enabled,
        String baseUrl,
        String apiKey,
        String apiSecret,
        String appId
    ) {
        // Compact constructor for defaults
        public ProviderConfig {
            if (baseUrl == null) baseUrl = "";
            if (apiKey == null) apiKey = "";
        }
    }
}
```

- [ ] **Step 3: Create LectureScript and SlideScript records**

```java
package org.example.educatorweb.resourcegen.model;

import java.util.List;

public record LectureScript(
    String title,
    List<SlideScript> slides,
    String teacherName,
    int estimatedDurationSeconds
) {}

public record SlideScript(
    int index,
    String title,
    List<String> bulletPoints,
    String narration,
    String visualPrompt,
    int durationSeconds
) {}
```

- [ ] **Step 4: Verify compilation**

Run: `export JAVA_HOME="/c/Users/x/.jdks/openjdk-25.0.2" && mvn compile`

Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/example/educatorweb/resourcegen/
git commit -m "feat(phase-a): add ModelProvider interface, ModelRoutingProperties, and LectureScript model"
```

---

### Task 2: Phase B — Provider Implementations & ModelRegistry

**Files:**
- Create: `src/main/java/org/example/educatorweb/resourcegen/infrastructure/DeepSeekProvider.java`
- Create: `src/main/java/org/example/educatorweb/resourcegen/infrastructure/OpenAiCompatibleProvider.java`
- Create: `src/main/java/org/example/educatorweb/resourcegen/infrastructure/XunfeiProvider.java`
- Create: `src/main/java/org/example/educatorweb/resourcegen/config/ModelRegistry.java`

- [ ] **Step 1: Create DeepSeekProvider — wraps existing ChatClient**

```java
package org.example.educatorweb.resourcegen.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;

public class DeepSeekProvider implements ModelProvider {
    private static final Logger log = LoggerFactory.getLogger(DeepSeekProvider.class);
    private final ChatClient chatClient;
    private final boolean enabled;

    public DeepSeekProvider(ChatClient chatClient, boolean enabled) {
        this.chatClient = chatClient;
        this.enabled = enabled;
    }

    @Override
    public String chat(String prompt) {
        log.debug("DeepSeekProvider: sending prompt ({} chars)", prompt.length());
        return chatClient.prompt().user(prompt).call().content();
    }

    @Override
    public String providerName() { return "deepseek"; }

    @Override
    public boolean isEnabled() { return enabled; }
}
```

- [ ] **Step 2: Create OpenAiCompatibleProvider — covers OpenAI/OpenRouter/SiliconFlow**

```java
package org.example.educatorweb.resourcegen.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

public class OpenAiCompatibleProvider implements ModelProvider {
    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleProvider.class);
    private final ChatClient chatClient;
    private final String name;
    private final boolean enabled;

    public OpenAiCompatibleProvider(String name, String baseUrl, String apiKey,
                                     String model, boolean enabled) {
        this.name = name;
        this.enabled = enabled;

        var httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofMinutes(3));

        var restClientBuilder = RestClient.builder().requestFactory(requestFactory);
        var api = OpenAiApi.builder()
            .baseUrl(baseUrl)
            .apiKey(apiKey)
            .restClientBuilder(restClientBuilder)
            .build();

        var chatModel = new OpenAiChatModel(api, OpenAiChatOptions.builder()
            .model(model).temperature(0.7).build());

        this.chatClient = ChatClient.builder(chatModel).build();
        log.info("OpenAiCompatibleProvider '{}' initialized: baseUrl={}, model={}", name, baseUrl, model);
    }

    @Override
    public String chat(String prompt) {
        return chatClient.prompt().user(prompt).call().content();
    }

    @Override
    public String providerName() { return name; }

    @Override
    public boolean isEnabled() { return enabled; }
}
```

- [ ] **Step 3: Create XunfeiProvider — stub for competition requirement**

```java
package org.example.educatorweb.resourcegen.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class XunfeiProvider implements ModelProvider {
    private static final Logger log = LoggerFactory.getLogger(XunfeiProvider.class);
    private final boolean enabled;

    public XunfeiProvider(boolean enabled, String appId, String apiKey, String apiSecret) {
        this.enabled = enabled;
        if (enabled) {
            log.warn("XunfeiProvider initialized but Spark API adapter not yet implemented. "
                + "AppId={}, will throw UnsupportedOperationException on chat().", appId);
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
```

- [ ] **Step 4: Create ModelRegistry — routes ResourceType → ModelProvider**

```java
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
            case VIDEO -> visualProvider.isEnabled() ? visualProvider : textProvider;
            default -> textProvider;
        };
    }
}
```

- [ ] **Step 5: Verify compilation**

Run: `export JAVA_HOME="/c/Users/x/.jdks/openjdk-25.0.2" && mvn compile`

Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/example/educatorweb/resourcegen/
git commit -m "feat(phase-b): add DeepSeekProvider, OpenAiCompatibleProvider, XunfeiProvider stub, and ModelRegistry"
```

---

### Task 3: Phase C — Generator Migration (AbstractGenerator + VideoGenerator + Config)

**Files:**
- Modify: `src/main/java/org/example/educatorweb/resourcegen/agents/generators/AbstractGenerator.java`
- Modify: `src/main/java/org/example/educatorweb/resourcegen/agents/generators/VideoGenerator.java`
- Modify: `src/main/java/org/example/educatorweb/resourcegen/config/ResourceGenConfig.java`

- [ ] **Step 1: Modify AbstractGenerator — ChatClient → ModelRegistry**

Change the constructor and field from `ChatClient` to `ModelRegistry`. All subclasses call `super(chatClient, type)` — update to `super(registry, type)`. Update `doGenerate` to use `registry.resolve(type).chat(prompt)`.

Read the current file first, then make these exact changes:

```java
// OLD field:
protected final ChatClient chatClient;

// NEW field:
protected final ModelRegistry registry;

// OLD constructor:
protected AbstractGenerator(ChatClient chatClient, ResourceType type) {
    this.chatClient = chatClient;
    this.type = type;
}

// NEW constructor:
protected AbstractGenerator(ModelRegistry registry, ResourceType type) {
    this.registry = registry;
    this.type = type;
}
```

In each subclass's `doGenerate()`, replace:
```java
// OLD:
return chatClient.prompt().user(prompt).call().content();

// NEW:
ModelProvider provider = registry.resolve(supportedType());
return provider.chat(prompt);
```

Update imports in AbstractGenerator and all subclasses. Subclasses to update:
- `DocGenerator.java`
- `MindmapGenerator.java`
- `QuizGenerator.java`
- `CodeGenerator.java`
- `HtmlGenerator.java`
- `VideoGenerator.java` (also gets phase 2 refactor below)

- [ ] **Step 2: Refactor VideoGenerator — two-phase generation**

Current VideoGenerator does: LLM → JSON outline → Apache POI → PPTX. Refactor to:

**Phase 1**: DeepSeek generates `LectureScript` (JSON with narration, visualPrompt)
**Phase 2**: Visual Provider (if enabled) generates PPTX; fallback to Apache POI

```java
@Override
protected String doGenerate(GenerationState state) {
    // Phase 1: DeepSeek generates lecture script
    ModelProvider textProvider = registry.resolve(ResourceType.DOC); // use text model
    LectureScript script = generateLectureScript(textProvider, state);
    
    // Phase 2: Visual provider generates PPTX (or fallback to V1)
    ModelProvider visualProvider = registry.resolve(supportedType()); // VIDEO → visual
    byte[] pptxBytes;
    if (visualProvider.isEnabled()) {
        pptxBytes = generateVisualPptx(visualProvider, script, state);
    } else {
        log.info("Visual provider disabled — falling back to Apache POI");
        pptxBytes = buildFallbackPptx(script, state);
    }
    
    String path = fileStorage.store(state.requestId(), pptxBytes,
        sanitizeFilename(state.knowledgePoint()) + ".pptx");
    return path;
}

private LectureScript generateLectureScript(ModelProvider provider, GenerationState state) {
    String prompt = buildPrompt(state);
    String response = provider.chat(prompt);
    try {
        return objectMapper.readValue(stripCodeFences(response), LectureScript.class);
    } catch (Exception e) {
        log.warn("Failed to parse LectureScript, using fallback: {}", e.getMessage());
        return buildFallbackScript(state);
    }
}

private byte[] generateVisualPptx(ModelProvider provider, LectureScript script, GenerationState state) {
    // Build a prompt from the lecture script's visualPrompts
    String visualPrompt = buildVisualPrompt(script);
    String response = provider.chat(visualPrompt);
    // Parse the visual provider's output into PPTX (format depends on provider)
    // For initial implementation: return the provider's response as-is or parse
    // If visual generation fails, fall through to buildFallbackPptx
    try {
        return parseVisualResponse(response, script);
    } catch (Exception e) {
        log.warn("Visual PPTX generation failed: {}", e.getMessage());
        return buildFallbackPptx(script, state);
    }
}

private byte[] buildFallbackPptx(LectureScript script, GenerationState state) {
    // V1 behavior: convert LectureScript slides to PptxBuilder.SlideData
    List<PptxBuilder.SlideData> slides = script.slides().stream()
        .map(s -> new PptxBuilder.SlideData(s.title(), s.bulletPoints(), s.narration()))
        .toList();
    return pptxBuilder.buildPresentation(script.title(), slides);
}

private String buildPrompt(GenerationState state) {
    // Updated prompt: ask LLM to output LectureScript JSON with narration and visualPrompt
    return """
        You are an expert educator creating a video lecture script.
        Topic: %s
        
        For each slide (5-10 slides), provide:
        - title, bulletPoints (shown on screen), narration (teacher's spoken words),
          visualPrompt (description for generating slide visuals), durationSeconds
        
        Output as JSON: {"title":"...","slides":[...],"teacherName":"李老师","estimatedDurationSeconds":N}
        """.formatted(state.knowledgePoint());
}
```

- [ ] **Step 3: Update ResourceGenConfig — Provider bean wiring**

Replace the `ChatClient` bean with `ModelRegistry` and Provider beans:

```java
@Bean
public ModelRegistry modelRegistry(
        ModelRoutingProperties routingProps,
        DeepSeekProvider deepSeekProvider,
        OpenAiCompatibleProvider openAiProvider,
        OpenAiCompatibleProvider openRouterProvider,
        OpenAiCompatibleProvider siliconFlowProvider,
        XunfeiProvider xunfeiProvider) {

    // Resolve text provider
    ModelProvider textProvider = switch (routingProps.text().provider()) {
        case "xunfei" -> xunfeiProvider;
        default -> deepSeekProvider;
    };

    // Resolve visual provider
    ModelProvider visualProvider = resolveVisualProvider(routingProps,
        openAiProvider, openRouterProvider, siliconFlowProvider, xunfeiProvider, deepSeekProvider);

    return new ModelRegistry(textProvider, visualProvider);
}

private ModelProvider resolveVisualProvider(ModelRoutingProperties props,
        OpenAiCompatibleProvider openAi, OpenAiCompatibleProvider openRouter,
        OpenAiCompatibleProvider siliconFlow, XunfeiProvider xunfei,
        DeepSeekProvider deepSeek) {
    var visualCfg = props.visual();
    return switch (visualCfg.provider()) {
        case "openai" -> openAi;
        case "openrouter" -> openRouter;
        case "siliconflow" -> siliconFlow;
        case "xunfei" -> xunfei;
        default -> deepSeek; // fallback: use text provider
    };
}

// Provider beans — each reads its config from ModelRoutingProperties
@Bean
public DeepSeekProvider deepSeekProvider(ModelRoutingProperties props) {
    var cfg = props.providers().get("deepseek");
    // ChatClient is still needed for DeepSeekProvider — create it inline
    String apiKey = System.getProperty("DEEPSEEK_API_KEY", System.getenv("DEEPSEEK_API_KEY"));
    var api = OpenAiApi.builder().baseUrl(cfg.baseUrl()).apiKey(apiKey)
        .restClientBuilder(longTimeoutRestClient()).build();
    var chatModel = new OpenAiChatModel(api,
        OpenAiChatOptions.builder().model(props.text().model()).temperature(props.text().temperature()).build());
    var chatClient = ChatClient.builder(chatModel).build();
    return new DeepSeekProvider(chatClient, cfg.enabled());
}

@Bean
public OpenAiCompatibleProvider openAiProvider(ModelRoutingProperties props) {
    var cfg = props.providers().get("openai");
    return new OpenAiCompatibleProvider("openai", cfg.baseUrl(), resolveKey(cfg, "OPENAI_API_KEY"),
        props.visual().model(), cfg.enabled());
}

@Bean
public OpenAiCompatibleProvider openRouterProvider(ModelRoutingProperties props) {
    var cfg = props.providers().get("openrouter");
    return new OpenAiCompatibleProvider("openrouter", cfg.baseUrl(), resolveKey(cfg, "OPENROUTER_API_KEY"),
        props.visual().model(), cfg.enabled());
}

@Bean
public OpenAiCompatibleProvider siliconFlowProvider(ModelRoutingProperties props) {
    var cfg = props.providers().get("siliconflow");
    return new OpenAiCompatibleProvider("siliconflow", cfg.baseUrl(), resolveKey(cfg, "SILICONFLOW_API_KEY"),
        props.visual().model(), cfg.enabled());
}

@Bean
public XunfeiProvider xunfeiProvider(ModelRoutingProperties props) {
    var cfg = props.providers().get("xunfei");
    return new XunfeiProvider(cfg.enabled(), cfg.appId(), cfg.apiKey(), cfg.apiSecret());
}

// Helper: read API key from YAML config's apiKey field (with ${ENV_VAR} resolution) or system props
private String resolveKey(ModelRoutingProperties.ProviderConfig cfg, String envVar) {
    String key = cfg.apiKey();
    if (key != null && !key.isBlank() && !key.startsWith("${")) return key;
    return System.getProperty(envVar, System.getenv().getOrDefault(envVar, ""));
}

// Helper: long timeout RestClient.Builder (reusable)
private RestClient.Builder longTimeoutRestClient() {
    var httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
    var requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(Duration.ofMinutes(3));
    return RestClient.builder().requestFactory(requestFactory);
}
```

Remove: the old `chatClient()` and `openAiChatModel()` and `openAiApi()` beans from the class — they're replaced by the Provider beans above. Keep the `graphOrchestrator()` bean unchanged.

- [ ] **Step 4: Verify compilation and fix issues**

Run: `export JAVA_HOME="/c/Users/x/.jdks/openjdk-25.0.2" && mvn compile`

Expected: BUILD SUCCESS. If subclass constructors break, update each Generator's constructor to pass `registry` instead of `chatClient`. All 6 generators' constructors need `AbstractGenerator(ModelRegistry registry, ResourceType type)` pattern.

- [ ] **Step 5: Run all tests**

Run: `export JAVA_HOME="/c/Users/x/.jdks/openjdk-25.0.2" && mvn test`

Expected: All 18 tests pass. Fix any compilation errors in test classes that reference the old `ChatClient` dependency.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/example/educatorweb/resourcegen/
git commit -m "feat(phase-c): migrate generators to ModelRegistry, refactor VideoGenerator to two-phase, update ResourceGenConfig"
```

---

### Task 4: Phase D — YAML Config + .env Extension

**Files:**
- Modify: `src/main/resources/application.yml`
- Modify: `.env.example`
- Modify: `src/main/java/org/example/educatorweb/resourcegen/config/DotenvConfig.java`

- [ ] **Step 1: Add model-routing config to application.yml**

Insert after the `spring:` block, before `logging:`:

```yaml
model-routing:
  text:
    provider: deepseek
    model: deepseek-chat
    temperature: 0.7
  visual:
    provider: deepseek              # default to deepseek (V1 fallback), change to siliconflow/openai/... when ready
    model: deepseek-chat
    temperature: 0.7
  providers:
    deepseek:
      enabled: true
      base-url: https://api.deepseek.com
      api-key: ${DEEPSEEK_API_KEY}
    siliconflow:
      enabled: false
      base-url: https://api.siliconflow.cn/v1
      api-key: ${SILICONFLOW_API_KEY:}
    openai:
      enabled: false
      base-url: https://api.openai.com
      api-key: ${OPENAI_API_KEY:}
    openrouter:
      enabled: false
      base-url: https://openrouter.ai/api
      api-key: ${OPENROUTER_API_KEY:}
    xunfei:
      enabled: false
      base-url: https://spark-api-open.xf-yun.com
      api-key: ${XUNFEI_API_KEY:}
      api-secret: ${XUNFEI_API_SECRET:}
      app-id: ${XUNFEI_APP_ID:}
    seedance:
      enabled: false
      base-url: https://api.seedance.io
      api-key: ${SEEDANCE_API_KEY:}
    huggingface:
      enabled: false
      base-url: https://api-inference.huggingface.co
      api-key: ${HUGGINGFACE_API_KEY:}
```

Also remove these redundant lines from the `spring.ai.openai` block since Provider beans now handle model config:
```yaml
# REMOVE from spring.ai.openai:
#   api-key: ${DEEPSEEK_API_KEY:sk-placeholder}
#   base-url: https://api.deepseek.com
#   chat:
#     options:
#       model: deepseek-chat
#       temperature: 0.7
```
Keep `spring.ai.openai.*` block but it can now be minimal — the Provider beans read from `model-routing.*` instead. BUT keep the log level `DEBUG` for `org.springframework.ai` for troubleshooting.

- [ ] **Step 2: Update .env.example — add new provider keys**

Add after the Xunfei section, before `# --- Databases ---`:

```env
# --- Visual/Multimedia Providers (for PPT/video generation) ---

# OpenAI (GPT-4o with vision, DALL-E)
OPENAI_API_KEY=sk-your-openai-key

# OpenRouter (unified API for many models)
OPENROUTER_API_KEY=sk-or-v1-your-key

# SiliconFlow (硅基流动 — Chinese provider, competitive pricing)
# Get yours at: https://siliconflow.cn/
SILICONFLOW_API_KEY=sk-your-siliconflow-key

# Seedance (video generation)
SEEDANCE_API_KEY=your-seedance-key

# HuggingFace Inference API
HUGGINGFACE_API_KEY=hf_your-huggingface-key
```

- [ ] **Step 3: Update DotenvConfig — add new keys to KEYS array**

Add these 5 entries to the `KEYS` array:
```java
"SILICONFLOW_API_KEY",
"OPENAI_API_KEY",
"OPENROUTER_API_KEY",
"SEEDANCE_API_KEY",
"HUGGINGFACE_API_KEY",
```

- [ ] **Step 4: Verify full compilation and tests**

Run: `export JAVA_HOME="/c/Users/x/.jdks/openjdk-25.0.2" && mvn clean compile && mvn test`

Expected: BUILD SUCCESS, all 18 tests pass.

- [ ] **Step 5: Manual smoke test**

Start the app and verify it works in V1 fallback mode (visual providers are all disabled by default):

```powershell
$env:JAVA_HOME="C:\Users\x\.jdks\openjdk-25.0.2"; $env:SPRING_PROFILES_ACTIVE="mock"; mvn spring-boot:run
```

Open `http://localhost:8080/test.html`, generate with VIDEO type checked. Should produce a PPTX file via V1 fallback.

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/application.yml .env.example src/main/java/org/example/educatorweb/resourcegen/config/DotenvConfig.java
git commit -m "feat(phase-d): add model-routing YAML config, extend .env with 5 new provider keys"
```

---

## Summary

| Task | Content | Files | Key Outcome |
|---|---|---|---|
| 1 (Phase A) | Interfaces + models | 3 new | Foundation types ready |
| 2 (Phase B) | Providers + Registry | 4 new | All providers implemented |
| 3 (Phase C) | Generator migration | 3 mod + 6 subclass touch | Generators use ModelRegistry |
| 4 (Phase D) | YAML + .env config | 3 mod | Config-driven routing active |

**Total: 7 new files, ~12 files modified, 4 commits**

### Verification Checklist

- [ ] `mvn clean compile` → BUILD SUCCESS
- [ ] `mvn test` → 18/18 pass
- [ ] App starts with mock profile
- [ ] `POST /api/generate` with `["DOC","MINDMAP"]` → works as before (DeepSeek)
- [ ] `POST /api/generate` with `["VIDEO"]` → PPTX generated (V1 fallback since no visual provider enabled)
- [ ] Startup log shows: `ModelRegistry: text=deepseek(enabled=true), visual=deepseek(enabled=true)`
