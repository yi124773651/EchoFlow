package com.echoflow.infrastructure.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * Multi-model routing configuration.
 *
 * <p>Binds to {@code echoflow.ai.routing} and {@code echoflow.ai.models} properties.
 * When routing aliases are empty or unset, {@link ChatClientProvider} falls back
 * to the auto-configured default ChatClient (from {@code spring.ai.openai.*}).</p>
 */
@ConfigurationProperties(prefix = "echoflow.ai")
public record MultiModelProperties(
        Map<String, ModelConfig> models,
        RoutingConfig routing
) {

    /**
     * Configuration for a single model provider endpoint.
     *
     * @param baseUrl OpenAI-compatible base URL (e.g. {@code https://dashscope.aliyuncs.com/compatible-mode/v1})
     * @param apiKey  API key for authentication
     * @param model   model name (e.g. {@code qwen-max}, {@code gpt-4o}, {@code deepseek-chat})
     */
    public record ModelConfig(String baseUrl, String apiKey, String model) {}

    /**
     * Maps step types to model aliases and defines a fallback alias.
     *
     * <p>Uses a {@code Map<String, String>} for step-to-alias mapping to avoid
     * the Java naming conflict with {@code Object.notify()}. Map keys are
     * lowercase step type names (think, research, write, notify).</p>
     *
     * @param stepAliases maps lowercase step type name to model alias
     * @param fallback    model alias for fallback when primary fails
     */
    public record RoutingConfig(
            Map<String, String> stepAliases,
            String fallback
    ) {
        public RoutingConfig {
            if (stepAliases == null) stepAliases = Map.of();
            if (fallback == null) fallback = "";
        }

        /**
         * Get the model alias for a given step type.
         *
         * @param stepType lowercase step type name (e.g. "think", "research")
         * @return model alias, or empty string if not configured
         */
        public String aliasFor(String stepType) {
            return stepAliases.getOrDefault(stepType.toLowerCase(), "");
        }
    }

    public MultiModelProperties {
        if (models == null) models = Map.of();
        if (routing == null) routing = new RoutingConfig(null, null);
    }
}
