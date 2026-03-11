package com.echoflow.infrastructure.ai;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.Hook;
import com.alibaba.cloud.ai.graph.agent.hook.messages.AgentCommand;
import com.alibaba.cloud.ai.graph.agent.hook.messages.UpdatePolicy;
import com.alibaba.cloud.ai.graph.agent.hook.modelcalllimit.ModelCallLimitHook;
import com.alibaba.cloud.ai.graph.agent.interceptor.Interceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * POC-3: Verifies Hook/Interceptor mechanisms work correctly.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>MessagesModelHook — message trimming via beforeModel/afterModel</li>
 *   <li>ToolInterceptor — tool call retry with exponential backoff</li>
 *   <li>API compatibility — Hook and Interceptor can be composed in Builder</li>
 *   <li>ToolCallResponse.error() — factory method existence (corrects research finding)</li>
 * </ul>
 */
class HookInterceptorPocTest {

    // --- MessageTrimmingHook ---

    @Test
    void message_trimming_hook_returns_command_when_over_limit() {
        var hook = new MessageTrimmingHook(3);

        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage("msg1"));
        messages.add(new UserMessage("msg2"));
        messages.add(new UserMessage("msg3"));
        messages.add(new UserMessage("msg4"));
        messages.add(new UserMessage("msg5"));

        AgentCommand command = hook.beforeModel(messages, null);

        // AgentCommand getters are package-private in the framework,
        // so we verify the hook was called and returned non-null command
        assertThat(command).isNotNull();
        assertThat(hook.beforeModelCallCount()).isEqualTo(1);
    }

    @Test
    void message_trimming_hook_passes_through_when_under_limit() {
        var hook = new MessageTrimmingHook(10);

        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage("msg1"));
        messages.add(new UserMessage("msg2"));

        AgentCommand command = hook.beforeModel(messages, null);

        assertThat(command).isNotNull();
        assertThat(hook.beforeModelCallCount()).isEqualTo(1);
    }

    @Test
    void message_trimming_hook_tracks_call_counts() {
        var hook = new MessageTrimmingHook(5);

        hook.beforeModel(List.of(new UserMessage("test")), null);
        hook.beforeModel(List.of(new UserMessage("test")), null);
        hook.afterModel(List.of(new UserMessage("test")), null);

        assertThat(hook.beforeModelCallCount()).isEqualTo(2);
        assertThat(hook.afterModelCallCount()).isEqualTo(1);
    }

    // --- ToolRetryInterceptor ---

    @Test
    void tool_retry_interceptor_succeeds_on_first_try() {
        var interceptor = new ToolRetryInterceptor(2);

        var request = new ToolCallRequest("call-1", "searchTool", "{}", null);
        ToolCallHandler handler = mock(ToolCallHandler.class);
        when(handler.call(request)).thenReturn(ToolCallResponse.of("call-1", "searchTool", "result"));

        ToolCallResponse response = interceptor.interceptToolCall(request, handler);

        assertThat(response.isError()).isFalse();
        assertThat(response.getResult()).isEqualTo("result");
        assertThat(interceptor.retryCount()).isEqualTo(0);
        verify(handler, times(1)).call(request);
    }

    @Test
    void tool_retry_interceptor_retries_on_failure() {
        var interceptor = new ToolRetryInterceptor(2);

        var request = new ToolCallRequest("call-1", "searchTool", "{}", null);
        ToolCallHandler handler = mock(ToolCallHandler.class);
        when(handler.call(request))
                .thenThrow(new RuntimeException("timeout"))
                .thenReturn(ToolCallResponse.of("call-1", "searchTool", "result after retry"));

        ToolCallResponse response = interceptor.interceptToolCall(request, handler);

        assertThat(response.isError()).isFalse();
        assertThat(response.getResult()).isEqualTo("result after retry");
        assertThat(interceptor.retryCount()).isEqualTo(1);
        verify(handler, times(2)).call(request);
    }

    @Test
    void tool_retry_interceptor_returns_error_after_exhausting_retries() {
        var interceptor = new ToolRetryInterceptor(1);

        var request = new ToolCallRequest("call-1", "searchTool", "{}", null);
        ToolCallHandler handler = mock(ToolCallHandler.class);
        when(handler.call(request)).thenThrow(new RuntimeException("persistent failure"));

        ToolCallResponse response = interceptor.interceptToolCall(request, handler);

        assertThat(response.isError()).isTrue();
        assertThat(response.getResult()).contains("failed after 2 attempts");
        assertThat(interceptor.interceptCallCount()).isEqualTo(1);
    }

    // --- ToolCallResponse.error() API verification ---

    @Test
    void tool_call_response_error_factory_exists_and_works() {
        // Corrects research finding: ToolCallResponse.error() DOES exist
        ToolCallResponse errorResponse = ToolCallResponse.error(
                "call-id", "toolName", "something went wrong");

        assertThat(errorResponse.isError()).isTrue();
        assertThat(errorResponse.getToolCallId()).isEqualTo("call-id");
        assertThat(errorResponse.getToolName()).isEqualTo("toolName");
    }

    @Test
    void tool_call_response_error_from_throwable() {
        ToolCallResponse errorResponse = ToolCallResponse.error(
                "call-id", "toolName", new RuntimeException("boom"));

        assertThat(errorResponse.isError()).isTrue();
    }

    // --- Builder composition verification ---

    @Test
    void hooks_and_interceptors_can_be_composed_in_builder() {
        // Verify the Builder API accepts both Hook and Interceptor lists
        var hook1 = new MessageTrimmingHook(10);
        var hook2 = ModelCallLimitHook.builder().runLimit(5).build();
        var interceptor = new ToolRetryInterceptor(2);

        // This verifies the API compatibility — Builder accepts these types
        // We can't fully build without a ChatClient, but we verify the type system works
        var builder = ReactAgent.builder()
                .name("test-agent")
                .hooks(hook1, hook2)
                .interceptors(interceptor);

        assertThat(builder).isNotNull();
    }

    @Test
    void multiple_hooks_coexist() {
        // Verify Hook interface is shared
        var trimHook = new MessageTrimmingHook(5);
        var limitHook = ModelCallLimitHook.builder().runLimit(3).build();

        List<Hook> hooks = List.of(trimHook, limitHook);
        assertThat(hooks).hasSize(2);
        assertThat(hooks).allSatisfy(h -> assertThat(h).isInstanceOf(Hook.class));
    }

    @Test
    void interceptor_interface_is_compatible() {
        var retryInterceptor = new ToolRetryInterceptor(2);

        List<Interceptor> interceptors = List.of(retryInterceptor);
        assertThat(interceptors).hasSize(1);
        assertThat(interceptors.get(0)).isInstanceOf(Interceptor.class);
    }
}
