package com.echoflow.application.execution;

import com.echoflow.application.execution.GraphOrchestrationPort.StepProgressListener;
import com.echoflow.domain.execution.Execution;
import com.echoflow.domain.execution.ExecutionRepository;
import com.echoflow.domain.execution.ExecutionStatus;
import com.echoflow.domain.execution.LogType;
import com.echoflow.domain.execution.StepType;
import com.echoflow.domain.task.Task;
import com.echoflow.domain.task.TaskId;
import com.echoflow.domain.task.TaskRepository;
import com.echoflow.domain.task.TaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionOperations;

import com.echoflow.domain.execution.ApprovalDecision;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExecuteTaskUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-03-10T12:00:00Z");

    @Mock private TaskRepository taskRepository;
    @Mock private ExecutionRepository executionRepository;
    @Mock private TaskPlannerPort taskPlanner;
    @Mock private GraphOrchestrationPort graphOrchestrator;

    private final List<ExecutionEvent> publishedEvents = Collections.synchronizedList(new ArrayList<>());
    private final ApprovalGateService approvalGateService = new ApprovalGateService();
    private ExecuteTaskUseCase useCase;

    @BeforeEach
    void setUp() {
        publishedEvents.clear();
        var clock = Clock.fixed(NOW, ZoneOffset.UTC);
        ExecutionEventPublisher publisher = publishedEvents::add;
        useCase = new ExecuteTaskUseCase(
                taskRepository, executionRepository, publisher,
                taskPlanner, graphOrchestrator, approvalGateService, clock,
                TransactionOperations.withoutTransaction(), false, 30);
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
    void execute_delegates_to_graph_orchestrator() {
        var taskId = TaskId.generate();
        var task = Task.submit(taskId, "调研 Java Agent", NOW);
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(taskPlanner.planSteps("调研 Java Agent")).thenReturn(List.of(
                new TaskPlannerPort.PlannedStep("分析需求", StepType.THINK),
                new TaskPlannerPort.PlannedStep("搜索资料", StepType.RESEARCH)
        ));

        // Simulate graph execution: each step completes
        doAnswer(invocation -> {
            StepProgressListener listener = invocation.getArgument(2);
            listener.onStepStarting("分析需求", StepType.THINK);
            listener.onStepCompleted("分析需求", "执行结果");
            listener.onStepStarting("搜索资料", StepType.RESEARCH);
            listener.onStepCompleted("搜索资料", "执行结果");
            return null;
        }).when(graphOrchestrator).executeSteps(eq("调研 Java Agent"), any(), any());

        useCase.execute(taskId);

        verify(graphOrchestrator).executeSteps(eq("调研 Java Agent"), any(), any());
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

        doAnswer(invocation -> {
            StepProgressListener listener = invocation.getArgument(2);
            listener.onStepStarting("思考", StepType.THINK);
            listener.onStepCompleted("思考", "执行结果");
            listener.onStepStarting("调研", StepType.RESEARCH);
            listener.onStepCompleted("调研", "执行结果");
            return null;
        }).when(graphOrchestrator).executeSteps(any(), any(), any());

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
    void execute_fails_when_graph_throws_exception() {
        var taskId = TaskId.generate();
        var task = Task.submit(taskId, "fail task", NOW);
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(taskPlanner.planSteps("fail task")).thenReturn(List.of(
                new TaskPlannerPort.PlannedStep("分析", StepType.THINK)
        ));

        doAnswer(invocation -> {
            StepProgressListener listener = invocation.getArgument(2);
            listener.onStepStarting("分析", StepType.THINK);
            listener.onStepFailed("分析", "unexpected NPE");
            throw new RuntimeException("unexpected NPE");
        }).when(graphOrchestrator).executeSteps(any(), any(), any());

        useCase.execute(taskId);

        assertThat(task.status()).isEqualTo(TaskStatus.FAILED);
        assertThat(publishedEvents.stream()
                .filter(e -> e instanceof ExecutionEvent.ExecutionFailed)
                .count()).isEqualTo(1);
    }

    @Test
    void execute_degrades_when_step_is_skipped() {
        var taskId = TaskId.generate();
        var task = Task.submit(taskId, "degraded task", NOW);
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(taskPlanner.planSteps("degraded task")).thenReturn(List.of(
                new TaskPlannerPort.PlannedStep("分析", StepType.THINK),
                new TaskPlannerPort.PlannedStep("调研", StepType.RESEARCH),
                new TaskPlannerPort.PlannedStep("撰写", StepType.WRITE)
        ));

        doAnswer(invocation -> {
            StepProgressListener listener = invocation.getArgument(2);
            listener.onStepStarting("分析", StepType.THINK);
            listener.onStepCompleted("分析", "分析结果");
            listener.onStepStarting("调研", StepType.RESEARCH);
            listener.onStepSkipped("调研", "LLM timeout");
            listener.onStepStarting("撰写", StepType.WRITE);
            listener.onStepCompleted("撰写", "报告内容");
            return null;
        }).when(graphOrchestrator).executeSteps(any(), any(), any());

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
    }

    @Test
    void execute_publishes_step_started_and_log_events() {
        var taskId = TaskId.generate();
        var task = Task.submit(taskId, "event test", NOW);
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(taskPlanner.planSteps("event test")).thenReturn(List.of(
                new TaskPlannerPort.PlannedStep("分析", StepType.THINK)
        ));

        doAnswer(invocation -> {
            StepProgressListener listener = invocation.getArgument(2);
            listener.onStepStarting("分析", StepType.THINK);
            listener.onStepCompleted("分析", "分析结果");
            return null;
        }).when(graphOrchestrator).executeSteps(any(), any(), any());

        useCase.execute(taskId);

        var eventTypes = publishedEvents.stream()
                .map(e -> e.getClass().getSimpleName())
                .toList();

        // Expected: ExecutionStarted → StepStarted → StepLogAppended(ACTION) →
        //           StepLogAppended(OBSERVATION) → StepCompleted → ExecutionCompleted
        assertThat(eventTypes).containsExactly(
                "ExecutionStarted",
                "StepStarted",
                "StepLogAppended",   // ACTION
                "StepLogAppended",   // OBSERVATION
                "StepCompleted",
                "ExecutionCompleted"
        );
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

    @Test
    void execute_handles_parallel_research_callbacks_safely() {
        var taskId = TaskId.generate();
        var task = Task.submit(taskId, "parallel task", NOW);
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(taskPlanner.planSteps("parallel task")).thenReturn(List.of(
                new TaskPlannerPort.PlannedStep("分析", StepType.THINK),
                new TaskPlannerPort.PlannedStep("搜索1", StepType.RESEARCH),
                new TaskPlannerPort.PlannedStep("搜索2", StepType.RESEARCH),
                new TaskPlannerPort.PlannedStep("撰写", StepType.WRITE)
        ));

        // Simulate parallel RESEARCH callbacks from two threads
        doAnswer(invocation -> {
            StepProgressListener listener = invocation.getArgument(2);

            // THINK runs first (sequential)
            listener.onStepStarting("分析", StepType.THINK);
            listener.onStepCompleted("分析", "分析结果");

            // Two RESEARCH steps fire concurrently
            var barrier = new CyclicBarrier(2);
            try (var executor = Executors.newFixedThreadPool(2)) {
                executor.submit(() -> {
                    try {
                        barrier.await();
                        listener.onStepStarting("搜索1", StepType.RESEARCH);
                        listener.onStepCompleted("搜索1", "结果1");
                    } catch (Exception e) { throw new RuntimeException(e); }
                });
                executor.submit(() -> {
                    try {
                        barrier.await();
                        listener.onStepStarting("搜索2", StepType.RESEARCH);
                        listener.onStepCompleted("搜索2", "结果2");
                    } catch (Exception e) { throw new RuntimeException(e); }
                });
            }

            // WRITE runs after both RESEARCH complete (sequential)
            listener.onStepStarting("撰写", StepType.WRITE);
            listener.onStepCompleted("撰写", "报告");
            return null;
        }).when(graphOrchestrator).executeSteps(any(), any(), any());

        useCase.execute(taskId);

        // All 4 steps completed, execution succeeded
        assertThat(task.status()).isEqualTo(TaskStatus.COMPLETED);

        var eventTypes = publishedEvents.stream()
                .map(e -> e.getClass().getSimpleName())
                .toList();
        assertThat(eventTypes.getFirst()).isEqualTo("ExecutionStarted");
        assertThat(eventTypes.getLast()).isEqualTo("ExecutionCompleted");
        assertThat(eventTypes.stream().filter(t -> t.equals("StepStarted")).count()).isEqualTo(4);
        assertThat(eventTypes.stream().filter(t -> t.equals("StepCompleted")).count()).isEqualTo(4);

        verify(executionRepository, atLeast(4)).save(any(Execution.class));
    }

    @Test
    void execute_onStepProgress_appends_log_and_publishes_event() {
        var taskId = TaskId.generate();
        var task = Task.submit(taskId, "review test", NOW);
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(taskPlanner.planSteps("review test")).thenReturn(List.of(
                new TaskPlannerPort.PlannedStep("撰写", StepType.WRITE)
        ));

        doAnswer(invocation -> {
            StepProgressListener listener = invocation.getArgument(2);
            listener.onStepStarting("撰写", StepType.WRITE);
            listener.onStepProgress("撰写", LogType.THOUGHT,
                    "Review: score 65/100. Needs more detail.");
            listener.onStepProgress("撰写", LogType.ACTION,
                    "Revising draft...");
            listener.onStepCompleted("撰写", "最终报告");
            return null;
        }).when(graphOrchestrator).executeSteps(any(), any(), any());

        useCase.execute(taskId);

        assertThat(task.status()).isEqualTo(TaskStatus.COMPLETED);

        var logEvents = publishedEvents.stream()
                .filter(e -> e instanceof ExecutionEvent.StepLogAppended)
                .map(e -> (ExecutionEvent.StepLogAppended) e)
                .toList();

        // ACTION (from onStepStarting) + THOUGHT (progress) + ACTION (progress) + OBSERVATION (from onStepCompleted)
        assertThat(logEvents).hasSize(4);
        assertThat(logEvents.get(1).logType()).isEqualTo(LogType.THOUGHT);
        assertThat(logEvents.get(1).content()).contains("score 65/100");
        assertThat(logEvents.get(2).logType()).isEqualTo(LogType.ACTION);
        assertThat(logEvents.get(2).content()).contains("Revising draft");
    }

    // --- Human Approval ---

    @Test
    void execute_pauses_at_write_step_and_resumes_on_approval() {
        var approvalUseCase = createApprovalEnabledUseCase();
        var taskId = TaskId.generate();
        var task = Task.submit(taskId, "approval test", NOW);
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(taskPlanner.planSteps("approval test")).thenReturn(List.of(
                new TaskPlannerPort.PlannedStep("分析", StepType.THINK),
                new TaskPlannerPort.PlannedStep("撰写", StepType.WRITE)
        ));

        doAnswer(invocation -> {
            StepProgressListener listener = invocation.getArgument(2);
            listener.onStepStarting("分析", StepType.THINK);
            listener.onStepCompleted("分析", "分析结果");
            listener.onStepStarting("撰写", StepType.WRITE);
            // This triggers the approval gate — run in a separate thread
            // to simulate the blocking behavior
            var decision = listener.onStepAwaitingApproval("撰写", StepType.WRITE);
            if (decision.approved()) {
                listener.onStepCompleted("撰写", "报告内容");
            }
            return null;
        }).when(graphOrchestrator).executeSteps(any(), any(), any());

        // Approve from another thread after a short delay
        Thread.startVirtualThread(() -> {
            try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            approvalGateService.decide(
                    publishedEvents.stream()
                            .filter(e -> e instanceof ExecutionEvent.StepAwaitingApproval)
                            .map(e -> ((ExecutionEvent.StepAwaitingApproval) e).executionId())
                            .findFirst()
                            .orElseThrow(),
                    ApprovalDecision.APPROVED);
        });

        approvalUseCase.execute(taskId);

        assertThat(task.status()).isEqualTo(TaskStatus.COMPLETED);

        var eventTypes = publishedEvents.stream()
                .map(e -> e.getClass().getSimpleName())
                .toList();
        assertThat(eventTypes).contains("StepAwaitingApproval", "StepApprovalDecided");
    }

    @Test
    void execute_skips_write_step_on_rejection() {
        var approvalUseCase = createApprovalEnabledUseCase();
        var taskId = TaskId.generate();
        var task = Task.submit(taskId, "reject test", NOW);
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(taskPlanner.planSteps("reject test")).thenReturn(List.of(
                new TaskPlannerPort.PlannedStep("撰写", StepType.WRITE),
                new TaskPlannerPort.PlannedStep("通知", StepType.NOTIFY)
        ));

        doAnswer(invocation -> {
            StepProgressListener listener = invocation.getArgument(2);
            listener.onStepStarting("撰写", StepType.WRITE);
            var decision = listener.onStepAwaitingApproval("撰写", StepType.WRITE);
            if (decision.approved()) {
                listener.onStepCompleted("撰写", "报告");
            } else {
                listener.onStepSkipped("撰写", "Rejected: " + decision.reason());
            }
            listener.onStepStarting("通知", StepType.NOTIFY);
            listener.onStepCompleted("通知", "已通知");
            return null;
        }).when(graphOrchestrator).executeSteps(any(), any(), any());

        // Reject from another thread
        Thread.startVirtualThread(() -> {
            try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            approvalGateService.decide(
                    publishedEvents.stream()
                            .filter(e -> e instanceof ExecutionEvent.StepAwaitingApproval)
                            .map(e -> ((ExecutionEvent.StepAwaitingApproval) e).executionId())
                            .findFirst()
                            .orElseThrow(),
                    ApprovalDecision.rejected("不合格"));
        });

        approvalUseCase.execute(taskId);

        assertThat(task.status()).isEqualTo(TaskStatus.COMPLETED);

        var eventTypes = publishedEvents.stream()
                .map(e -> e.getClass().getSimpleName())
                .toList();
        assertThat(eventTypes).contains("StepAwaitingApproval", "StepApprovalDecided", "StepSkipped");
    }

    @Test
    void execute_auto_approves_non_write_steps() {
        var approvalUseCase = createApprovalEnabledUseCase();
        var taskId = TaskId.generate();
        var task = Task.submit(taskId, "non-write test", NOW);
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(taskPlanner.planSteps("non-write test")).thenReturn(List.of(
                new TaskPlannerPort.PlannedStep("分析", StepType.THINK)
        ));

        doAnswer(invocation -> {
            StepProgressListener listener = invocation.getArgument(2);
            listener.onStepStarting("分析", StepType.THINK);
            // THINK should auto-approve even with approval enabled
            var decision = listener.onStepAwaitingApproval("分析", StepType.THINK);
            assertThat(decision).isEqualTo(ApprovalDecision.APPROVED);
            listener.onStepCompleted("分析", "结果");
            return null;
        }).when(graphOrchestrator).executeSteps(any(), any(), any());

        approvalUseCase.execute(taskId);

        assertThat(task.status()).isEqualTo(TaskStatus.COMPLETED);
        // No StepAwaitingApproval event for non-WRITE steps
        assertThat(publishedEvents.stream()
                .filter(e -> e instanceof ExecutionEvent.StepAwaitingApproval)
                .count()).isZero();
    }

    private ExecuteTaskUseCase createApprovalEnabledUseCase() {
        var clock = Clock.fixed(NOW, ZoneOffset.UTC);
        ExecutionEventPublisher publisher = publishedEvents::add;
        return new ExecuteTaskUseCase(
                taskRepository, executionRepository, publisher,
                taskPlanner, graphOrchestrator, approvalGateService, clock,
                TransactionOperations.withoutTransaction(),
                true, 1);
    }
}
