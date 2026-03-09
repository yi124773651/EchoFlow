package com.echoflow.domain.task;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class TaskTest {

    private static final Instant NOW = Instant.parse("2026-03-10T12:00:00Z");
    private static final Instant LATER = NOW.plusSeconds(60);

    // --- Creation ---

    @Test
    void submit_creates_task_in_submitted_status() {
        var id = TaskId.generate();
        var task = Task.submit(id, "调研 Java Agent 项目", NOW);

        assertThat(task.id()).isEqualTo(id);
        assertThat(task.description()).isEqualTo("调研 Java Agent 项目");
        assertThat(task.status()).isEqualTo(TaskStatus.SUBMITTED);
        assertThat(task.createdAt()).isEqualTo(NOW);
        assertThat(task.completedAt()).isNull();
    }

    @Test
    void submit_strips_whitespace_from_description() {
        var task = Task.submit(TaskId.generate(), "  带空格的描述  ", NOW);
        assertThat(task.description()).isEqualTo("带空格的描述");
    }

    @Test
    void submit_rejects_null_description() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> Task.submit(TaskId.generate(), null, NOW));
    }

    @Test
    void submit_rejects_blank_description() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> Task.submit(TaskId.generate(), "   ", NOW));
    }

    // --- State transitions ---

    @Test
    void markExecuting_transitions_from_submitted() {
        var task = aSubmittedTask();
        task.markExecuting();
        assertThat(task.status()).isEqualTo(TaskStatus.EXECUTING);
    }

    @Test
    void markCompleted_transitions_from_executing() {
        var task = anExecutingTask();
        task.markCompleted(LATER);
        assertThat(task.status()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(task.completedAt()).isEqualTo(LATER);
    }

    @Test
    void markFailed_transitions_from_executing() {
        var task = anExecutingTask();
        task.markFailed(LATER);
        assertThat(task.status()).isEqualTo(TaskStatus.FAILED);
        assertThat(task.completedAt()).isEqualTo(LATER);
    }

    // --- Invalid transitions ---

    @Test
    void markExecuting_from_executing_throws() {
        var task = anExecutingTask();
        assertThatThrownBy(task::markExecuting)
                .isInstanceOf(IllegalTaskStateException.class);
    }

    @Test
    void markCompleted_from_submitted_throws() {
        var task = aSubmittedTask();
        assertThatThrownBy(() -> task.markCompleted(LATER))
                .isInstanceOf(IllegalTaskStateException.class);
    }

    @Test
    void markFailed_from_submitted_throws() {
        var task = aSubmittedTask();
        assertThatThrownBy(() -> task.markFailed(LATER))
                .isInstanceOf(IllegalTaskStateException.class);
    }

    @Test
    void markCompleted_from_completed_throws() {
        var task = anExecutingTask();
        task.markCompleted(LATER);
        assertThatThrownBy(() -> task.markCompleted(LATER))
                .isInstanceOf(IllegalTaskStateException.class);
    }

    @Test
    void markFailed_from_failed_throws() {
        var task = anExecutingTask();
        task.markFailed(LATER);
        assertThatThrownBy(() -> task.markFailed(LATER))
                .isInstanceOf(IllegalTaskStateException.class);
    }

    // --- TaskId ---

    @Test
    void taskId_rejects_null() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new TaskId(null));
    }

    @Test
    void taskId_generate_produces_unique_ids() {
        var a = TaskId.generate();
        var b = TaskId.generate();
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void taskId_equality_by_value() {
        var uuid = UUID.randomUUID();
        assertThat(new TaskId(uuid)).isEqualTo(new TaskId(uuid));
    }

    // --- Helpers ---

    private Task aSubmittedTask() {
        return Task.submit(TaskId.generate(), "test task", NOW);
    }

    private Task anExecutingTask() {
        var task = aSubmittedTask();
        task.markExecuting();
        return task;
    }
}
