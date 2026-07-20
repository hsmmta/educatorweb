package org.example.educatorweb.resourcegen.config;

import org.example.educatorweb.resourcegen.infrastructure.CheckpointService;
import org.example.educatorweb.resourcegen.infrastructure.DeepSeekProvider;
import org.example.educatorweb.resourcegen.infrastructure.ModelProvider;
import org.example.educatorweb.resourcegen.infrastructure.OpenAiCompatibleProvider;
import org.example.educatorweb.resourcegen.infrastructure.SeedanceVideoProvider;
import org.example.educatorweb.resourcegen.infrastructure.StaticImageFallbackProvider;
import org.example.educatorweb.resourcegen.infrastructure.VideoProvider;
import org.example.educatorweb.resourcegen.infrastructure.XunfeiProvider;
import org.example.educatorweb.resourcegen.orchestration.GraphOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
@EnableConfigurationProperties(ModelRoutingProperties.class)
public class ResourceGenConfig {

    private static final Logger log = LoggerFactory.getLogger(ResourceGenConfig.class);

    @Value("${generation.fanout.thread-pool-size:6}")
    private int threadPoolSize;

    @Autowired(required = false)
    private CheckpointService checkpointService;

    // ---- ModelRegistry bean: wires text and visual providers ----

    @Bean
    public ModelRegistry modelRegistry(
            ModelRoutingProperties routingProps,
            DeepSeekProvider deepSeekProvider,
            OpenAiCompatibleProvider openAiProvider,
            OpenAiCompatibleProvider openRouterProvider,
            OpenAiCompatibleProvider siliconFlowProvider,
            XunfeiProvider xunfeiProvider) {

        ModelProvider textProvider = switch (routingProps.text().provider()) {
            case "xunfei" -> xunfeiProvider;
            default -> deepSeekProvider;
        };

        ModelProvider visualProvider = switch (routingProps.visual().provider()) {
            case "openai" -> openAiProvider;
            case "openrouter" -> openRouterProvider;
            case "siliconflow" -> siliconFlowProvider;
            case "xunfei" -> xunfeiProvider;
            default -> deepSeekProvider;
        };

        return new ModelRegistry(textProvider, visualProvider);
    }

    // ---- VideoProvider bean ----

    @Bean
    public VideoProvider videoProvider(ModelRoutingProperties props,
                                        OpenAiCompatibleProvider openAiProvider,
                                        OpenAiCompatibleProvider openRouterProvider,
                                        OpenAiCompatibleProvider siliconFlowProvider) {
        // Check if video provider is configured and enabled
        var videoCfg = props.video();
        if (videoCfg == null || videoCfg.provider() == null) {
            log.info("No video provider configured, using StaticImageFallbackProvider");
            return new StaticImageFallbackProvider(openAiProvider);
        }

        var providerCfg = props.providers().get(videoCfg.provider());
        if (providerCfg == null || !providerCfg.enabled()) {
            log.info("Video provider '{}' not enabled, using StaticImageFallbackProvider",
                videoCfg.provider());
            return new StaticImageFallbackProvider(openAiProvider);
        }

        log.info("Creating VideoProvider: {}", videoCfg.provider());
        return switch (videoCfg.provider()) {
            case "seedance" -> new SeedanceVideoProvider(
                providerCfg.baseUrl(),
                resolveEnvKey(providerCfg.apiKey(), "SEEDANCE_API_KEY"),
                videoCfg.model(),
                props.videoImageModel(),
                true);
            default -> {
                log.warn("Unknown video provider '{}', falling back to StaticImageFallbackProvider",
                    videoCfg.provider());
                yield new StaticImageFallbackProvider(openAiProvider);
            }
        };
    }

    // ---- Provider beans ----

    @Bean
    public DeepSeekProvider deepSeekProvider(ModelRoutingProperties props) {
        var cfg = props.providers().get("deepseek");
        String apiKey = System.getProperty("DEEPSEEK_API_KEY", System.getenv("DEEPSEEK_API_KEY"));
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("DEEPSEEK_API_KEY not set — AI features will fail at runtime. Using placeholder to allow startup.");
            apiKey = "sk-no-key-configured";
        }
        var restClientBuilder = longTimeoutRestClient();
        var api = OpenAiApi.builder().baseUrl(cfg.baseUrl()).apiKey(apiKey)
            .restClientBuilder(restClientBuilder).build();
        var chatModel = new OpenAiChatModel(api,
            OpenAiChatOptions.builder().model(props.text().model()).temperature(props.text().temperature()).build());
        var chatClient = ChatClient.builder(chatModel).build();
        return new DeepSeekProvider(chatClient, cfg.enabled());
    }

    @Bean
    public OpenAiCompatibleProvider openAiProvider(ModelRoutingProperties props) {
        var cfg = props.providers().get("openai");
        return new OpenAiCompatibleProvider("openai", cfg.baseUrl(),
            resolveEnvKey(cfg.apiKey(), "OPENAI_API_KEY"),
            props.visual().model(), cfg.enabled());
    }

    @Bean
    public OpenAiCompatibleProvider openRouterProvider(ModelRoutingProperties props) {
        var cfg = props.providers().get("openrouter");
        return new OpenAiCompatibleProvider("openrouter", cfg.baseUrl(),
            resolveEnvKey(cfg.apiKey(), "OPENROUTER_API_KEY"),
            props.visual().model(), cfg.enabled());
    }

    @Bean
    public OpenAiCompatibleProvider siliconFlowProvider(ModelRoutingProperties props) {
        var cfg = props.providers().get("siliconflow");
        return new OpenAiCompatibleProvider("siliconflow", cfg.baseUrl(),
            resolveEnvKey(cfg.apiKey(), "SILICONFLOW_API_KEY"),
            props.visual().model(), cfg.enabled());
    }

    @Bean
    public XunfeiProvider xunfeiProvider(ModelRoutingProperties props) {
        var cfg = props.providers().get("xunfei");
        return new XunfeiProvider(cfg.enabled(), cfg.appId(), cfg.apiKey(), cfg.apiSecret());
    }

    // ---- Orchestrator ----

    @Bean(destroyMethod = "shutdown")
    public GraphOrchestrator graphOrchestrator() {
        GraphOrchestrator orchestrator = new GraphOrchestrator(threadPoolSize);
        if (checkpointService != null) {
            orchestrator.setCheckpointService(checkpointService);
        }
        return orchestrator;
    }

    // ---- Helpers ----

    /**
     * Resolve API key: prefer YAML literal value, fallback to env var / system property.
     */
    private String resolveEnvKey(String yamlValue, String envVar) {
        if (yamlValue != null && !yamlValue.isBlank() && !yamlValue.startsWith("${")) {
            return yamlValue;
        }
        String sysProp = System.getProperty(envVar);
        if (sysProp != null && !sysProp.isBlank()) return sysProp;
        String env = System.getenv(envVar);
        return env != null ? env : "";
    }

    /**
     * RestClient.Builder with reasonable timeouts for LLM calls.
     * 
     * Forces HTTP/1.1 because Java's HttpClient (HTTP/2 by default) can hang
     * on certain server/CDN configurations (observed with api.deepseek.com).
     * curl (HTTP/1.1) works reliably against the same endpoint.
     */
    private RestClient.Builder longTimeoutRestClient() {
        var httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)  // prevent HTTP/2 compatibility hangs
            .connectTimeout(Duration.ofSeconds(30)).build();
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(60));
        return RestClient.builder().requestFactory(requestFactory);
    }
}
