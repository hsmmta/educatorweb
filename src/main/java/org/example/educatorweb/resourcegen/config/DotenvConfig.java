package org.example.educatorweb.resourcegen.config;

import io.github.cdimascio.dotenv.Dotenv;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
public class DotenvConfig implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    private static final Logger log = LoggerFactory.getLogger(DotenvConfig.class);
    private static final String[] KEYS = {
        "DEEPSEEK_API_KEY",
        "MYSQL_PASSWORD",
        "NEO4J_URI",
        "NEO4J_USERNAME",
        "NEO4J_PASSWORD",
        "REDIS_PASSWORD",
        "XUNFEI_API_KEY",
        "XUNFEI_API_SECRET",
        "XUNFEI_APP_ID",
        "SILICONFLOW_API_KEY",
        "OPENAI_API_KEY",
        "OPENROUTER_API_KEY",
        "SEEDANCE_API_KEY",
        "HUGGINGFACE_API_KEY",
        "QDRANT_HOST",
        "QDRANT_API_KEY",
        "MEM0_API_KEY"
    };

    @Override
    public void initialize(ConfigurableApplicationContext ctx) {
        try {
            Dotenv dotenv = Dotenv.configure()
                .directory(".")
                .ignoreIfMissing()
                .load();

            Map<String, Object> props = new HashMap<>();
            boolean loaded = false;

            for (String key : KEYS) {
                String value = dotenv.get(key);
                if (value != null) {
                    System.setProperty(key, value);
                    props.put(key, value);
                    loaded = true;
                }
            }

            if (loaded) {
                ctx.getEnvironment().getPropertySources()
                    .addFirst(new MapPropertySource("dotenv", props));
                log.info(".env file loaded successfully");
            } else {
                log.info("No .env file found — using default values");
            }
        } catch (Exception e) {
            log.warn("Failed to load .env file: {}", e.getMessage());
        }
    }
}
