package com.echoflow.application.execution;

import com.echoflow.domain.execution.Execution;
import com.echoflow.domain.execution.ExecutionRepository;
import com.echoflow.domain.execution.ExecutionStatus;
import com.echoflow.domain.execution.StepType;
import com.echoflow.domain.task.Task;
import com.echoflow.domain.task.TaskId;
import com.echoflow.domain.task.TaskRepository;
import com.echoflow.domain.task.TaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExecuteTaskUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-03-10T12:00:00Z");

    @Mock private TaskRepository taskRepository;
    @Mock private ExecutionRepository executionRepository;
    @Mock private TaskPlannerPort taskPlanner;
    @Mock private StepExecutorPort stepExecutor;

    private final List<ExecutionEvent> publishedEvents = new ArrayList<>();
    private ExecuteTaskUseCase useCase;

    @BeforeEach
    void setUp() {
        publishedEvents.clear();
        var clock = Clock.fixed(NOW, ZoneOffset.UTC);
        ExecutionEventPublisher publisher = publishedEvents::add;
        useCase = new ExecuteTaskUseCase(
                taskRepository, executionRepository, publisher,
                taskPlanner, stepExecutor, clock,
                TransactionOperations.withoutTransaction());
    }

    @Test
    void planExecution_uses_llm_planned_steps() {
        var taskId = TaskId.generate();
        var task = Task.submit(taskId, "调研 Java Agent 项目", NOW);
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(taskPlanner.planSteps("调研 Java Agent 项目")).thenReturn(List.of(
                new TaskPlannerPort.PlannedStep("分析需求", StepType.THINK),
                new TaskPlannerPort.PlannedStep("搜索 GitHub", StepType.RESEARCH),
                new TaskPlannerPort.PlannedStep("撰写报告", StepType.WRITE),
                new TaskPlannerPort.PlannedStep("邮件通知", StepType.NOTIFY)
        ));

        var execution = useCase.planExecution(taskId);

        assertThat(execution.status()).isEqualTo(ExecutionStatus.RUNNING);
        assertThat(execution.steps()).hasSize(4);
        assertThat(execution.steps().get(0).name()).isEqualTo("分析需求");
        assertThat(execution.steps().get(1).name()).isEqualTo("搜索 GitHub");
        assertThat(execution.steps().get(2).name()).isEqualTo("撰写报告");
        assertThat(execution.steps().get(3).name()).isEqualTo("邮件通知");

        assertThat(task.status()).isEqualTo(TaskStatus.EXECUTING);
        verify(taskPlanner).planSteps("调研 Java Agent 项目");

        assertThat(publishedEvents).hasSize(1);
        assertThat(publishedEvents.getFirst()).isInstanceOf(ExecutionEvent.ExecutionStarted.class);
    }

    @Test
    void planExecution_throws_when_planner_returns_empty() {
        var taskId = TaskId.generate();
        var task = Task.submit(taskId, "empty task", NOW);
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(taskPlanner.planSteps("empty task")).thenReturn(List.of());

        assertThatThrownBy(() -> useCase.planExecution(taskId))
                .isInstanceOf(TaskPlanningException.class);
    }

    @Test
    void execute_delegates_each_step_to_step_executor() {
        var taskId = TaskId.generate();
        var task = Task.submit(taskId, "调研 Java Agent", NOW);
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(taskPlanner.planSteps("调研 Java Agent")).thenReturn(List.of(
                new TaskPlannerPort.PlannedStep("分析需求", StepType.THINK),
                new TaskPlannerPort.PlannedStep("搜索资料", StepType.RESEARCH)
        ));
        when(stepExecutor.execute(any())).thenReturn(new StepOutput("执行结果"));

        useCase.execute(taskId);

        verify(stepExecutor, times(2)).execute(any(StepExecutionContext.class));
    }

    @Test
    void execute_passes_previous_outputs_to_subsequent_steps() {
        var taskId = TaskId.generate();
        var task = Task.submit(taskId, "写报告", NOW);
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(taskPlanner.planSteps("写报告")).thenReturn(List.of(
                new TaskPlannerPort.PlannedStep("分析", StepType.THINK),
                new TaskPlannerPort.PlannedStep("撰写", StepType.WRITE)
        ));

        when(stepExecutor.execute(argThat(ctx ->
                ctx != null && ctx.stepType() == StepType.THINK)))
                .thenReturn(new StepOutput("分析结果"));
        when(stepExecutor.execute(argThat(ctx ->
                ctx != null && ctx.stepType() == StepType.WRITE)))
                .thenReturn(new StepOutput("# 报告\n\n详细内容"));

        useCase.execute(taskId);

        var captor = ArgumentCaptor.forClass(StepExecutionContext.class);
        verify(stepExecutor, times(2)).execute(captor.capture());
        var contexts = captor.getAllValues();

        assertThat(contexts.get(0).previousOutputs()).isEmpty();
        assertThat(contexts.get(1).previousOutputs()).containsExactly("分析结果");
    }

    @Test
    void execute_runs_all_steps_and_completes() {
        var taskId = TaskId.generate();
        var task = Task.submit(taskId, "test task", NOW);
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(taskPlanner.planSteps("test task")).thenReturn(List.of(
                new TaskPlannerPort.PlannedStep("思考", StepType.THINK),
                new TaskPlannerPort.PlannedStep("调研", StepType.RESEARCH)
        ));
        when(stepExecutor.execute(any())).thenReturn(new StepOutput("执行结果"));

        useCase.execute(taskId);

        assertThat(task.status()).isEqualTo(TaskStatus.COMPLETED);

        var eventTypes = publishedEvents.stream()
                .map(e -> e.getClass().getSimpleName())
                .toList();

        assertThat(eventTypes.getFirst()).isEqualTo("ExecutionStarted");
        assertThat(eventTypes.getLast()).isEqualTo("ExecutionCompleted");
        assertThat(eventTypes.stream().filter(t -> t.equals("StepStarted")).count()).isEqualTo(2);
        assertThat(eventTypes.stream().filter(t -> t.equals("StepCompleted")).count()).isEqualTo(2);

        verify(executionRepository, atLeast(2)).save(any(Execution.class));
    }

    @Test
    void execute_fails_on_unexpected_exception() {
        var taskId = TaskId.generate();
        var task = Task.submit(taskId, "fail task", NOW);
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(taskPlanner.planSteps("fail task")).thenReturn(List.of(
                new TaskPlannerPort.PlannedStep("分析", StepType.THINK)
        ));
        when(stepExecutor.execute(any()))
                .thenThrow(new RuntimeException("unexpected NPE"));

        useCase.execute(taskId);

        assertThat(task.status()).isEqualTo(TaskStatus.FAILED);
        assertThat(publishedEvents.stream()
                .filter(e -> e instanceof ExecutionEvent.ExecutionFailed)
                .count()).isEqualTo(1);
    }

    @Test
    void execute_degrades_when_step_execution_fails() {
        var taskId = TaskId.generate();
        var task = Task.submit(taskId, "degraded task", NOW);
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(taskPlanner.planSteps("degraded task")).thenReturn(List.of(
                new TaskPlannerPort.PlannedStep("分析", StepType.THINK),
                new TaskPlannerPort.PlannedStep("调研", StepType.RESEARCH),
                new TaskPlannerPort.PlannedStep("撰写", StepType.WRITE)
        ));

        when(stepExecutor.execute(argThat(ctx ->
                ctx != null && ctx.stepType() == StepType.THINK)))
                .thenReturn(new StepOutput("分析结果"));
        when(stepExecutor.execute(argThat(ctx ->
                ctx != null && ctx.stepType() == StepType.RESEARCH)))
                .thenThrow(new StepExecutionException("LLM timeout"));
        when(stepExecutor.execute(argThat(ctx ->
                ctx != null && ctx.stepType() == StepType.WRITE)))
                .thenReturn(new StepOutput("报告内容"));

        useCase.execute(taskId);

        // Task completed (not failed) despite one step being skipped
        assertThat(task.status()).isEqualTo(TaskStatus.COMPLETED);

        var eventTypes = publishedEvents.stream()
                .map(e -> e.getClass().getSimpleName())
                .toList();
        assertThat(eventTypes).contains("StepSkipped");
        assertThat(eventTypes.getLast()).isEqualTo("ExecutionCompleted");
        assertThat(eventTypes.stream().filter(t -> t.equals("StepCompleted")).count()).isEqualTo(2);
        assertThat(eventTypes.stream().filter(t -> t.equals("StepSkipped")).count()).isEqualTo(1);

        // All 3 steps were attempted
        verify(stepExecutor, times(3)).execute(any(StepExecutionContext.class));
    }

    @Test
    void execute_skipped_step_output_not_in_previous_outputs() {
        var taskId = TaskId.generate();
        var task = Task.submit(taskId, "skip output test", NOW);
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(taskPlanner.planSteps("skip output test")).thenReturn(List.of(
                new TaskPlannerPort.PlannedStep("分析", StepType.THINK),
                new TaskPlannerPort.PlannedStep("调研", StepType.RESEARCH),
                new TaskPlannerPort.PlannedStep("撰写", StepType.WRITE)
        ));

        when(stepExecutor.execute(argThat(ctx ->
                ctx != null && ctx.stepType() == StepType.THINK)))
                .thenReturn(new StepOutput("分析结果"));
        when(stepExecutor.execute(argThat(ctx ->
                ctx != null && ctx.stepType() == StepType.RESEARCH)))
                .thenThrow(new StepExecutionException("LLM timeout"));
        when(stepExecutor.execute(argThat(ctx ->
                ctx != null && ctx.stepType() == StepType.WRITE)))
                .thenReturn(new StepOutput("报告"));

        useCase.execute(taskId);

        // Verify WRITE step only received THINK output, not the skipped RESEARCH output
        var captor = ArgumentCaptor.forClass(StepExecutionContext.class);
        verify(stepExecutor, times(3)).execute(captor.capture());
        var contexts = captor.getAllValues();

        // THINK: no previous
        assertThat(contexts.get(0).previousOutputs()).isEmpty();
        // RESEARCH: has THINK output (then throws)
        assertThat(contexts.get(1).previousOutputs()).containsExactly("分析结果");
        // WRITE: only THINK output (RESEARCH was skipped)
        assertThat(contexts.get(2).previousOutputs()).containsExactly("分析结果");
    }

    @Test
    void execute_fails_gracefully_when_planner_throws() {
        var taskId = TaskId.generate();
        var task = Task.submit(taskId, "bad task", NOW);
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(taskPlanner.planSteps("bad task")).thenThrow(
                new TaskPlanningException("LLM timeout"));

        assertThatThrownBy(() -> useCase.execute(taskId))
                .isInstanceOf(TaskPlanningException.class);
    }
}
