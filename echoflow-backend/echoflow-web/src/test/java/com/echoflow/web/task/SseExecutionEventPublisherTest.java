package com.echoflow.web.task;

import com.echoflow.application.execution.ExecutionEvent;
import com.echoflow.domain.execution.ExecutionId;
import com.echoflow.domain.execution.LogType;
import com.echoflow.domain.execution.StepId;
import com.echoflow.domain.task.TaskId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SseExecutionEventPublisherTest {

    private SseExecutionEventPublisher publisher;

    private final TaskId taskId = TaskId.generate();
    private final ExecutionId executionId = ExecutionId.generate();
    private final StepId stepId = StepId.generate();
    private final Instant now = Instant.now();

    @BeforeEach
    void setUp() {
        publisher = new SseExecutionEventPublisher();
    }

    /**
     * BUG REPRODUCTION: Events published between ExecutionStarted (buffered)
     * and SSE connection are silently dropped.
     *
     * Timeline:
     *   T1: publish(ExecutionStarted) → buffered (no emitter yet) ✓
     *   T2: publish(StepStarted)      → executionToTask exists, emitter missing → DROPPED ✗
     *   T3: publish(StepLogAppended)   → same → DROPPED ✗
     *   T4: register() → replays ExecutionStarted only
     *
     * Expected: ALL events between T1-T4 should be buffered and replayed.
     */
    @Test
    void should_buffer_all_events_when_emitter_not_yet_registered() {
        // Given: backend publishes events BEFORE frontend SSE connects
        publisher.publish(new ExecutionEvent.ExecutionStarted(
                executionId, taskId,
                List.of(new ExecutionEvent.ExecutionStarted.StepInfo(stepId, 1, "分析", "THINK")),
                now
        ));
        publisher.publish(new ExecutionEvent.StepStarted(
                executionId, stepId, "分析", now
        ));
        publisher.publish(new ExecutionEvent.StepLogAppended(
                executionId, stepId, LogType.THOUGHT, "正在思考...", now
        ));

        // Then: all 3 events should be in the pending buffer, not just ExecutionStarted
        var pending = publisher.drainPendingEvents(taskId);
        assertThat(pending).hasSize(3);
        assertThat(pending.get(0)).isInstanceOf(ExecutionEvent.ExecutionStarted.class);
        assertThat(pending.get(1)).isInstanceOf(ExecutionEvent.StepStarted.class);
        assertThat(pending.get(2)).isInstanceOf(ExecutionEvent.StepLogAppended.class);
    }

    @Test
    void should_buffer_full_execution_before_sse_connects() {
        // Given: entire execution completes before SSE connects
        publisher.publish(new ExecutionEvent.ExecutionStarted(
                executionId, taskId,
                List.of(new ExecutionEvent.ExecutionStarted.StepInfo(stepId, 1, "分析", "THINK")),
                now
        ));
        publisher.publish(new ExecutionEvent.StepStarted(executionId, stepId, "分析", now));
        publisher.publish(new ExecutionEvent.StepLogAppended(
                executionId, stepId, LogType.THOUGHT, "思考中", now));
        publisher.publish(new ExecutionEvent.StepLogAppended(
                executionId, stepId, LogType.ACTION, "调用工具", now));
        publisher.publish(new ExecutionEvent.StepCompleted(executionId, stepId, "完成", now));
        publisher.publish(new ExecutionEvent.ExecutionCompleted(executionId, now));

        // Then: all 6 events buffered in order
        var pending = publisher.drainPendingEvents(taskId);
        assertThat(pending).hasSize(6);
        assertThat(pending.get(0)).isInstanceOf(ExecutionEvent.ExecutionStarted.class);
        assertThat(pending.get(5)).isInstanceOf(ExecutionEvent.ExecutionCompleted.class);
    }

    @Test
    void should_not_buffer_when_emitter_already_registered() {
        // Given: SSE already connected
        publisher.register(taskId);

        // When: events published
        publisher.publish(new ExecutionEvent.ExecutionStarted(
                executionId, taskId,
                List.of(new ExecutionEvent.ExecutionStarted.StepInfo(stepId, 1, "分析", "THINK")),
                now
        ));

        // Then: no pending events (sent directly to emitter)
        var pending = publisher.drainPendingEvents(taskId);
        assertThat(pending).isEmpty();
    }

    @Test
    void should_return_empty_list_for_unknown_task() {
        var pending = publisher.drainPendingEvents(TaskId.generate());
        assertThat(pending).isEmpty();
    }
}
