package com.echoflow.infrastructure.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.api.OpenAiApi;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChatClientProviderTest {

    @Mock private OpenAiApi baseApi;
    @Mock private OpenAiChatModel baseChatModel;
    @Mock private ChatClient.Builder defaultBuilder;
    @Mock private ChatClient defaultChatClient;

    @Test
    void returns_default_for_null_alias() {
        when(defaultBuilder.build()).thenReturn(defaultChatClient);
        var props = new MultiModelProperties(Map.of(), null);

        var provider = new ChatClientProvider(baseApi, baseChatModel, defaultBuilder, props);

        assertThat(provider.resolve(null)).isSameAs(defaultChatClient);
    }

    @Test
    void returns_default_for_empty_alias() {
        when(defaultBuilder.build()).thenReturn(defaultChatClient);
        var props = new MultiModelProperties(Map.of(), null);

        var provider = new ChatClientProvider(baseApi, baseChatModel, defaultBuilder, props);

        assertThat(provider.resolve("")).isSameAs(defaultChatClient);
    }

    @Test
    void returns_default_for_blank_alias() {
        when(defaultBuilder.build()).thenReturn(defaultChatClient);
        var props = new MultiModelProperties(Map.of(), null);

        var provider = new ChatClientProvider(baseApi, baseChatModel, defaultBuilder, props);

        assertThat(provider.resolve("  ")).isSameAs(defaultChatClient);
    }

    @Test
    void returns_default_for_unknown_alias() {
        when(defaultBuilder.build()).thenReturn(defaultChatClient);
        var props = new MultiModelProperties(Map.of(), null);

        var provider = new ChatClientProvider(baseApi, baseChatModel, defaultBuilder, props);

        assertThat(provider.resolve("nonexistent")).isSameAs(defaultChatClient);
    }

    @Test
    void maps_alias_with_empty_base_url_to_default() {
        when(defaultBuilder.build()).thenReturn(defaultChatClient);
        var config = new MultiModelProperties.ModelConfig("", "key", "model");
        var props = new MultiModelProperties(Map.of("alias-default", config), null);

        var provider = new ChatClientProvider(baseApi, baseChatModel, defaultBuilder, props);

        assertThat(provider.resolve("alias-default")).isSameAs(defaultChatClient);
    }

    @Test
    void maps_alias_with_null_base_url_to_default() {
        when(defaultBuilder.build()).thenReturn(defaultChatClient);
        var config = new MultiModelProperties.ModelConfig(null, "key", "model");
        var props = new MultiModelProperties(Map.of("alias-null", config), null);

        var provider = new ChatClientProvider(baseApi, baseChatModel, defaultBuilder, props);

        assertThat(provider.resolve("alias-null")).isSameAs(defaultChatClient);
    }

    @Test
    void creates_custom_client_for_alias_with_base_url() {
        when(defaultBuilder.build()).thenReturn(defaultChatClient);

        // Mock the mutate chain for OpenAiApi
        var apiBuilder = mock(OpenAiApi.Builder.class);
        var customApi = mock(OpenAiApi.class);
        when(baseApi.mutate()).thenReturn(apiBuilder);
        when(apiBuilder.baseUrl(any())).thenReturn(apiBuilder);
        when(apiBuilder.apiKey(any(String.class))).thenReturn(apiBuilder);
        when(apiBuilder.build()).thenReturn(customApi);

        // Mock the mutate chain for OpenAiChatModel
        var modelBuilder = mock(OpenAiChatModel.Builder.class);
        var customModel = mock(OpenAiChatModel.class);
        when(baseChatModel.mutate()).thenReturn(modelBuilder);
        when(modelBuilder.openAiApi(any())).thenReturn(modelBuilder);
        when(modelBuilder.defaultOptions(any())).thenReturn(modelBuilder);
        when(modelBuilder.build()).thenReturn(customModel);

        var config = new MultiModelProperties.ModelConfig(
                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "sk-test",
                "qwen-max");
        var props = new MultiModelProperties(Map.of("dashscope-strong", config), null);

        var provider = new ChatClientProvider(baseApi, baseChatModel, defaultBuilder, props);
        var resolved = provider.resolve("dashscope-strong");

        assertThat(resolved).isNotSameAs(defaultChatClient);
        verify(baseApi).mutate();
        verify(apiBuilder).baseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1");
        verify(apiBuilder).apiKey("sk-test");
        verify(baseChatModel).mutate();
    }

    @Test
    void default_client_accessor_returns_same_instance() {
        when(defaultBuilder.build()).thenReturn(defaultChatClient);
        var props = new MultiModelProperties(Map.of(), null);

        var provider = new ChatClientProvider(baseApi, baseChatModel, defaultBuilder, props);

        assertThat(provider.defaultClient()).isSameAs(defaultChatClient);
    }
}
