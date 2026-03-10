package com.echoflow.application.execution;

import com.echoflow.domain.execution.*;
import com.echoflow.domain.task.TaskId;

import java.time.Instant;
import java.util.List;

/**
 * SSE event types emitted during task execution.
 * The Web layer listens to these and pushes them to the client.
 */
public sealed interface ExecutionEvent {

    ExecutionId executionId();
    Instant timestamp();

    record ExecutionStarted(ExecutionId executionId, TaskId taskId,
                            List<StepInfo> steps, Instant timestamp) implements ExecutionEvent {
        public record StepInfo(StepId stepId, int order, String name, String type) {}
    }

    record StepStarted(ExecutionId executionId, StepId stepId,
                       String name, Instant timestamp) implements ExecutionEvent {}

    record StepLogAppended(ExecutionId executionId, StepId stepId,
                           LogType logType, String content, Instant timestamp) implements ExecutionEvent {}

    record StepCompleted(ExecutionId executionId, StepId stepId,
                         String output, Instant timestamp) implements ExecutionEvent {}

    record StepFailed(ExecutionId executionId, StepId stepId,
                      String reason, Instant timestamp) implements ExecutionEvent {}

    record StepSkipped(ExecutionId executionId, StepId stepId,
                       String reason, Instant timestamp) implements ExecutionEvent {}

    record ExecutionCompleted(ExecutionId executionId, Instant timestamp) implements ExecutionEvent {}

    record ExecutionFailed(ExecutionId executionId, String reason, Instant timestamp) implements ExecutionEvent {}
}
