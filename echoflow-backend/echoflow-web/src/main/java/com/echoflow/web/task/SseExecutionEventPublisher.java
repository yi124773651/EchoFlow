package com.echoflow.web.task;

import com.echoflow.application.execution.ExecutionEvent;
import com.echoflow.application.execution.ExecutionEventPublisher;
import com.echoflow.domain.execution.ExecutionId;
import com.echoflow.domain.task.TaskId;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory SSE event publisher that routes execution events to
 * the correct SSE connection based on taskId.
 *
 * Events published before the SSE connection is established are
 * buffered and replayed when {@link #register(TaskId)} is called.
 */
@Component
public class SseExecutionEventPublisher implements ExecutionEventPublisher {

    private final Map<TaskId, SseEmitter> emitters = new ConcurrentHashMap<>();
    private final Map<ExecutionId, TaskId> executionToTask = new ConcurrentHashMap<>();
    private final Map<TaskId, List<ExecutionEvent>> pendingEvents = new ConcurrentHashMap<>();

    /**
     * Register an SSE emitter for a specific task.
     * Replays all buffered events in order.
     */
    public SseEmitter register(TaskId taskId) {
        var emitter = new SseEmitter(0L); // no timeout
        emitters.put(taskId, emitter);
        emitter.onCompletion(() -> { emitters.remove(taskId); pendingEvents.remove(taskId); });
        emitter.onTimeout(() -> { emitters.remove(taskId); pendingEvents.remove(taskId); });
        emitter.onError(e -> { emitters.remove(taskId); pendingEvents.remove(taskId); });

        // Replay ALL pending events if backend published before SSE connected
        var pending = pendingEvents.remove(taskId);
        if (pending != null) {
            for (var event : pending) {
                try {
                    emitter.send(SseEmitter.event()
                            .name(event.getClass().getSimpleName())
                            .data(event));
                } catch (IOException e) {
                    emitter.completeWithError(e);
                    emitters.remove(taskId);
                    return emitter;
                }
            }
        }

        return emitter;
    }

    @Override
    public void publish(ExecutionEvent event) {
        // Track executionId → taskId from ExecutionStarted
        if (event instanceof ExecutionEvent.ExecutionStarted started) {
            executionToTask.put(started.executionId(), started.taskId());
        }

        var taskId = (event instanceof ExecutionEvent.ExecutionStarted started)
                ? started.taskId()
                : executionToTask.get(event.executionId());
        if (taskId == null) return;

        // Buffer if emitter not yet registered
        if (!emitters.containsKey(taskId)) {
            pendingEvents.computeIfAbsent(taskId, k -> Collections.synchronizedList(new ArrayList<>()))
                    .add(event);
            return;
        }

        var emitter = emitters.get(taskId);
        if (emitter == null) return;

        try {
            var eventType = event.getClass().getSimpleName();
            emitter.send(SseEmitter.event()
                    .name(eventType)
                    .data(event));

            // Complete emitter on terminal events
            if (event instanceof ExecutionEvent.ExecutionCompleted
                    || event instanceof ExecutionEvent.ExecutionFailed) {
                emitter.complete();
                executionToTask.remove(event.executionId());
            }
        } catch (IOException e) {
            emitter.completeWithError(e);
            emitters.remove(taskId);
            executionToTask.remove(event.executionId());
        }
    }

    /**
     * Drain and return all pending events for a task. Used for testing.
     */
    List<ExecutionEvent> drainPendingEvents(TaskId taskId) {
        var events = pendingEvents.remove(taskId);
        return events != null ? List.copyOf(events) : List.of();
    }
}
