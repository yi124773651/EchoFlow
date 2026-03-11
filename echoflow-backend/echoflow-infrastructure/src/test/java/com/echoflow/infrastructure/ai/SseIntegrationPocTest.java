package com.echoflow.infrastructure.ai;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * POC-5: Verifies SSE integration capability.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>ReactAgent.stream() returns Flux&lt;NodeOutput&gt;</li>
 *   <li>StreamingOutput.chunk() contains usable text</li>
 *   <li>NodeOutput can be adapted to the existing SSE event format</li>
 *   <li>NodeOutput type detection (instanceof StreamingOutput)</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class SseIntegrationPocTest {

    @Mock
    private ReactAgent reactAgent;

    private static OverAllState emptyState() {
        return new OverAllState();
    }

    /**
     * Helper to create StreamingOutput without generic ambiguity.
     * When T=String, the (T, String, String, OverAllState) and (String, String, String, OverAllState)
     * constructors are ambiguous. Using the 5-arg constructor resolves this.
     */
    private static StreamingOutput<Object> streamingOutput(String chunk, String node, String agent) {
        return new StreamingOutput<>(chunk, (Object) chunk, node, agent, emptyState());
    }

    @Test
    void streaming_output_provides_chunk_text() {
        var output = streamingOutput("Hello, this is a ", "agent_node", "think_executor");

        assertThat(output.chunk()).isEqualTo("Hello, this is a ");
        assertThat(output.node()).isEqualTo("agent_node");
        assertThat(output.agent()).isEqualTo("think_executor");
    }

    @Test
    void node_output_can_be_detected_as_streaming() {
        NodeOutput output = streamingOutput("chunk data", "agent_node", "think_executor");

        // Verify pattern matching works (used in SSE adapter)
        if (output instanceof StreamingOutput<?> streaming) {
            assertThat(streaming.chunk()).isEqualTo("chunk data");
        } else {
            throw new AssertionError("Expected StreamingOutput instance");
        }
    }

    @Test
    void streaming_output_extends_node_output() {
        var output = streamingOutput("content", "agent_node", "think_executor");

        assertThat(output).isInstanceOf(NodeOutput.class);
        assertThat(output.state()).isNotNull();
        assertThat(output.state()).isInstanceOf(OverAllState.class);
    }

    @Test
    void stream_returns_flux_of_node_output() throws GraphRunnerException {
        Flux<NodeOutput> fakeStream = Flux.just(
                streamingOutput("Part 1 ", "node", "agent"),
                streamingOutput("Part 2 ", "node", "agent")
        );

        when(reactAgent.stream(anyString())).thenReturn(fakeStream);

        Flux<NodeOutput> stream = reactAgent.stream("analyze this task");

        StepVerifier.create(stream)
                .expectNextCount(2)
                .verifyComplete();
    }

    @Test
    void stream_can_be_mapped_to_sse_event_chunks() throws GraphRunnerException {
        Flux<NodeOutput> fakeStream = Flux.just(
                streamingOutput("Part 1 ", "node", "think"),
                streamingOutput("Part 2 ", "node", "think"),
                streamingOutput("Part 3.", "node", "think")
        );

        when(reactAgent.stream(anyString())).thenReturn(fakeStream);

        // Simulate SSE adapter: map NodeOutput → String chunks
        Flux<String> sseChunks = reactAgent.stream("task description")
                .filter(output -> output instanceof StreamingOutput<?>)
                .map(output -> ((StreamingOutput<?>) output).chunk());

        StepVerifier.create(sseChunks)
                .expectNext("Part 1 ")
                .expectNext("Part 2 ")
                .expectNext("Part 3.")
                .verifyComplete();
    }

    @Test
    void stream_messages_returns_flux_of_messages() throws GraphRunnerException {
        when(reactAgent.streamMessages(anyString())).thenReturn(Flux.empty());

        var messageStream = reactAgent.streamMessages("task description");

        StepVerifier.create(messageStream)
                .verifyComplete();
    }

    @Test
    void streaming_output_chunk_concatenation_produces_full_text() throws GraphRunnerException {
        Flux<NodeOutput> fakeStream = Flux.just(
                streamingOutput("The ", "n", "a"),
                streamingOutput("task ", "n", "a"),
                streamingOutput("requires ", "n", "a"),
                streamingOutput("analysis.", "n", "a")
        );

        when(reactAgent.stream(anyString())).thenReturn(fakeStream);

        // Verify chunks can be concatenated to reconstruct full output
        String fullText = reactAgent.stream("task")
                .filter(output -> output instanceof StreamingOutput<?>)
                .map(output -> ((StreamingOutput<?>) output).chunk())
                .reduce("", String::concat)
                .block();

        assertThat(fullText).isEqualTo("The task requires analysis.");
    }
}
