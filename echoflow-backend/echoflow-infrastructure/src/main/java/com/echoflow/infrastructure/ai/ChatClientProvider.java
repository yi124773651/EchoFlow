package com.echoflow.infrastructure.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Creates and caches {@link ChatClient} instances for each configured model alias.
 *
 * <p>Uses Spring AI 1.1.2's {@code mutate()} API on the auto-configured
 * {@link OpenAiApi} and {@link OpenAiChatModel} beans to derive provider-specific
 * instances with different base URLs, API keys, and model names.</p>
 *
 * <p>When a model alias is empty, blank, or not found in the configuration,
 * the default auto-configured ChatClient is returned.</p>
 */
@Component
class ChatClientProvider {

    private static final Logger log = LoggerFactory.getLogger(ChatClientProvider.class);

    private final Map<String, ChatClient> clients;
    private final ChatClient defaultClient;

    ChatClientProvider(OpenAiApi baseApi,
                       OpenAiChatModel baseChatModel,
                       ChatClient.Builder defaultBuilder,
                       MultiModelProperties properties) {
        this.defaultClient = defaultBuilder.build();
        this.clients = new HashMap<>();

        for (var entry : properties.models().entrySet()) {
            var alias = entry.getKey();
            var config = entry.getValue();

            if (config.baseUrl() == null || config.baseUrl().isBlank()) {
                clients.put(alias, defaultClient);
                log.info("Model alias '{}' mapped to default ChatClient", alias);
            } else {
                var api = baseApi.mutate()
                        .baseUrl(config.baseUrl())
                        .apiKey(config.apiKey())
                        .build();
                var model = baseChatModel.mutate()
                        .openAiApi(api)
                        .defaultOptions(OpenAiChatOptions.builder()
                                .model(config.model())
                                .build())
                        .build();
                clients.put(alias, ChatClient.builder(model).build());
                log.info("Model alias '{}' configured: base-url={}, model={}",
                        alias, config.baseUrl(), config.model());
            }
        }
    }

    /**
     * Resolve a {@link ChatClient} by model alias.
     *
     * @param alias model alias from routing config, or empty/null for default
     * @return the corresponding ChatClient, or the default if alias is unknown
     */
    ChatClient resolve(String alias) {
        if (alias == null || alias.isBlank()) {
            return defaultClient;
        }
        return clients.getOrDefault(alias, defaultClient);
    }

    ChatClient defaultClient() {
        return defaultClient;
    }
}
