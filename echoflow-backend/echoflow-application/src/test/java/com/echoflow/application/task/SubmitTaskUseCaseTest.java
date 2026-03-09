package com.echoflow.application.task;

import com.echoflow.domain.task.Task;
import com.echoflow.domain.task.TaskRepository;
import com.echoflow.domain.task.TaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SubmitTaskUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-03-10T12:00:00Z");

    @Mock
    private TaskRepository taskRepository;

    private SubmitTaskUseCase useCase;

    @BeforeEach
    void setUp() {
        var clock = Clock.fixed(NOW, ZoneOffset.UTC);
        useCase = new SubmitTaskUseCase(taskRepository, clock);
    }

    @Test
    void execute_creates_and_saves_task() {
        var command = new SubmitTaskCommand("调研 Java Agent 项目");

        var result = useCase.execute(command);

        assertThat(result.id()).isNotNull();
        assertThat(result.description()).isEqualTo("调研 Java Agent 项目");
        assertThat(result.status()).isEqualTo(TaskStatus.SUBMITTED);
        assertThat(result.createdAt()).isEqualTo(NOW);

        var captor = ArgumentCaptor.forClass(Task.class);
        verify(taskRepository).save(captor.capture());
        var saved = captor.getValue();
        assertThat(saved.id()).isEqualTo(result.id());
        assertThat(saved.status()).isEqualTo(TaskStatus.SUBMITTED);
    }

    @Test
    void execute_rejects_blank_description() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SubmitTaskCommand("  "));
    }
}
