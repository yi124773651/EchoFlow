package com.echoflow.application.execution;

import com.echoflow.domain.execution.*;
import com.echoflow.domain.task.Task;
import com.echoflow.domain.task.TaskId;
import com.echoflow.domain.task.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExecutionRecoveryServiceTest {

    private static final Instant NOW = Instant.parse("2026-03-20T12:00:00Z");

    @Mock private ExecutionRepository executionRepository;
    @Mock private TaskRepository taskRepository;
    @Mock private GraphOrchestrationPort graphOrchestrator;

    private final List<ExecutionEvent> publishedEvents = Collections.synchronizedList(new ArrayList<>());
    private final ApprovalGateService approvalGateService = new ApprovalGateService();
    private ExecutionRecoveryService recoveryService;

    @BeforeEach
    void setUp() {
        publishedEvents.clear();
        var clock = Clock.fixed(NOW, ZoneOffset.UTC);
        ExecutionEventPublisher publisher = publishedEvents::add;
        recoveryService = new ExecutionRecoveryService(
                executionRepository, taskRepository, publisher,
                graphOrchestrator, approvalGateService, clock,
                TransactionOperations.withoutTransaction(),
                true, 30);
    }

    // --- RUNNING recovery ---

    @Test
    void marks_orphaned_running_executions_as_failed() {
        var taskId = TaskId.generate();
        var task = Task.submit(taskId, "test", NOW);
        task.markExecuting();

        var execution = createRunningExecution(taskId);
        when(executionRepository.findByStatus(ExecutionStatus.RUNNING))
                .thenReturn(List.of(execution));
        when(executionRepository.findByStatus(ExecutionStatus.WAITING_APPROVAL))
                .thenReturn(List.of());
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));

        recoveryService.recoverInterruptedExecutions();

        assertThat(execution.status()).isEqualTo(ExecutionStatus.FAILED);
        verify(executionRepository).save(execution);
        verify(graphOrchestrator).releaseCheckpoints(execution.id());

        assertThat(publishedEvents).hasSize(1);
        assertThat(publishedEvents.getFirst()).isInstanceOf(ExecutionEvent.ExecutionFailed.class);
    }

    @Test
    void no_op_when_nothing_to_recover() {
        when(executionRepository.findByStatus(ExecutionStatus.RUNNING))
                .thenReturn(List.of());
        when(executionRepository.findByStatus(ExecutionStatus.WAITING_APPROVAL))
                .thenReturn(List.of());

        recoveryService.recoverInterruptedExecutions();

        verify(executionRepository, never()).save(any());
        assertThat(publishedEvents).isEmpty();
    }

    // --- WAITING_APPROVAL recovery ---

    @Test
    void recovers_waiting_approval_execution_on_approve() throws Exception {
        var taskId = TaskId.generate();
        var task = Task.submit(taskId, "approval task", NOW);
        task.markExecuting();

        var execution = createWaitingApprovalExecution(taskId);
        when(executionRepository.findByStatus(ExecutionStatus.RUNNING))
                .thenReturn(List.of());
        when(executionRepository.findByStatus(ExecutionStatus.WAITING_APPROVAL))
                .thenReturn(List.of(execution));
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));

        // Simulate graph execution for remaining steps
        doAnswer(invocation -> {
            GraphOrchestrationPort.StepProgressListener listener = invocation.getArgument(3);
            listener.onStepStarting("撰写", StepType.WRITE);
            listener.onStepCompleted("撰写", "报告内容");
            return null;
        }).when(graphOrchestrator).executeSteps(any(), any(), any(), any());

        recoveryService.recoverInterruptedExecutions();

        // Wait for virtual thread to complete
        Thread.sleep(100);

        // Approve the gate
        approvalGateService.decide(execution.id(), ApprovalDecision.APPROVED);

        // Wait for recovery to finish
        Thread.sleep(200);

        assertThat(execution.status()).isEqualTo(ExecutionStatus.COMPLETED);

        var eventTypes = publishedEvents.stream()
                .map(e -> e.getClass().getSimpleName())
                .toList();
        assertThat(eventTypes).contains("StepAwaitingApproval", "StepApprovalDecided");
    }

    @Test
    void recovers_waiting_approval_execution_on_reject() throws Exception {
        var taskId = TaskId.generate();
        var task = Task.submit(taskId, "reject task", NOW);
        task.markExecuting();

        var execution = createWaitingApprovalExecution(taskId);
        // No pending steps after the waiting step
        when(executionRepository.findByStatus(ExecutionStatus.RUNNING))
                .thenReturn(List.of());
        when(executionRepository.findByStatus(ExecutionStatus.WAITING_APPROVAL))
                .thenReturn(List.of(execution));
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));

        recoveryService.recoverInterruptedExecutions();

        // Wait for virtual thread
        Thread.sleep(100);

        // Reject the gate
        approvalGateService.decide(execution.id(), ApprovalDecision.rejected("不合格"));

        Thread.sleep(200);

        // Step should be skipped, execution should complete (no remaining steps)
        var waitingStep = execution.steps().stream()
                .filter(s -> s.name().equals("撰写"))
                .findFirst().orElseThrow();
        assertThat(waitingStep.status()).isEqualTo(StepStatus.SKIPPED);
        assertThat(execution.status()).isEqualTo(ExecutionStatus.COMPLETED);
    }

    @Test
    void fails_recovery_when_no_waiting_step_found() throws Exception {
        var taskId = TaskId.generate();
        var task = Task.submit(taskId, "bad data", NOW);
        task.markExecuting();

        // Execution is WAITING_APPROVAL but no step is actually waiting
        var execution = Execution.create(ExecutionId.generate(), taskId, NOW);
        execution.addStep(StepId.generate(), "分析", StepType.THINK, NOW);
        execution.startRunning();
        var step = execution.startNextStep();
        execution.completeStep(step.id(), "done");
        execution.markWaitingApproval(); // inconsistent: no step is waiting

        when(executionRepository.findByStatus(ExecutionStatus.RUNNING))
                .thenReturn(List.of());
        when(executionRepository.findByStatus(ExecutionStatus.WAITING_APPROVAL))
                .thenReturn(List.of(execution));
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));

        recoveryService.recoverInterruptedExecutions();

        Thread.sleep(200);

        // Should be marked FAILED due to inconsistent state
        assertThat(execution.status()).isEqualTo(ExecutionStatus.FAILED);
    }

    // --- Helpers ---

    private Execution createRunningExecution(TaskId taskId) {
        var execution = Execution.create(ExecutionId.generate(), taskId, NOW);
        execution.addStep(StepId.generate(), "分析", StepType.THINK, NOW);
        execution.startRunning();
        return execution;
    }

    private Execution createWaitingApprovalExecution(TaskId taskId) {
        var execution = Execution.create(ExecutionId.generate(), taskId, NOW);
        execution.addStep(StepId.generate(), "分析", StepType.THINK, NOW);
        execution.addStep(StepId.generate(), "撰写", StepType.WRITE, NOW);
        execution.startRunning();
        var thinkStep = execution.startStepByName("分析");
        execution.completeStep(thinkStep.id(), "分析结果");
        var writeStep = execution.startStepByName("撰写");
        execution.markStepWaitingApproval(writeStep.id());
        execution.markWaitingApproval();
        return execution;
    }
}
