package com.echoflow.application.execution;

import com.echoflow.domain.execution.*;
import com.echoflow.domain.task.TaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Recovers interrupted executions on application startup.
 *
 * <p>RUNNING executions are marked FAILED (no safe way to resume mid-step).
 * WAITING_APPROVAL executions are resumed: the approval gate is re-created
 * and a virtual thread waits for user decision. When approved, remaining
 * steps are executed via a new StateGraph.</p>
 */
@Service
public class ExecutionRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(ExecutionRecoveryService.class);

    private final ExecutionRepository executionRepository;
    private final TaskRepository taskRepository;
    private final ExecutionEventPublisher eventPublisher;
    private final GraphOrchestrationPort graphOrchestrator;
    private final ApprovalGateService approvalGateService;
    private final Clock clock;
    private final TransactionOperations tx;
    private final boolean approvalEnabled;
    private final int approvalTimeoutMinutes;

    public ExecutionRecoveryService(ExecutionRepository executionRepository,
                                    TaskRepository taskRepository,
                                    ExecutionEventPublisher eventPublisher,
                                    GraphOrchestrationPort graphOrchestrator,
                                    ApprovalGateService approvalGateService,
                                    Clock clock,
                                    TransactionOperations tx,
                                    @Value("${echoflow.approval.enabled:false}") boolean approvalEnabled,
                                    @Value("${echoflow.approval.timeout-minutes:30}") int approvalTimeoutMinutes) {
        this.executionRepository = executionRepository;
        this.taskRepository = taskRepository;
        this.eventPublisher = eventPublisher;
        this.graphOrchestrator = graphOrchestrator;
        this.approvalGateService = approvalGateService;
        this.clock = clock;
        this.tx = tx;
        this.approvalEnabled = approvalEnabled;
        this.approvalTimeoutMinutes = approvalTimeoutMinutes;
    }

    /**
     * Recover executions that were interrupted by process restart.
     */
    public void recoverInterruptedExecutions() {
        failOrphanedRunningExecutions();
        recoverWaitingApprovalExecutions();
    }

    private void failOrphanedRunningExecutions() {
        var running = executionRepository.findByStatus(ExecutionStatus.RUNNING);
        for (var execution : running) {
            log.warn("Marking interrupted RUNNING execution {} as FAILED", execution.id());
            var now = clock.instant();
            execution.markFailed(now);
            tx.executeWithoutResult(status -> {
                executionRepository.save(execution);
                taskRepository.findById(execution.taskId()).ifPresent(task -> {
                    task.markFailed(now);
                    taskRepository.save(task);
                });
            });
            graphOrchestrator.releaseCheckpoints(execution.id());
            eventPublisher.publish(new ExecutionEvent.ExecutionFailed(
                    execution.id(), "Process restart during execution", now));
        }
    }

    private void recoverWaitingApprovalExecutions() {
        var waiting = executionRepository.findByStatus(ExecutionStatus.WAITING_APPROVAL);
        for (var execution : waiting) {
            log.info("Recovering WAITING_APPROVAL execution {}", execution.id());
            Thread.startVirtualThread(() -> recoverSingleExecution(execution));
        }
    }

    private void recoverSingleExecution(Execution execution) {
        try {
            var waitingStep = execution.findWaitingApprovalStep()
                    .orElseThrow(() -> new IllegalStateException(
                            "Execution " + execution.id()
                                    + " is WAITING_APPROVAL but no step is waiting"));

            // Re-publish SSE event so frontend can display the approval UI
            eventPublisher.publish(new ExecutionEvent.StepAwaitingApproval(
                    execution.id(), waitingStep.id(), waitingStep.name(),
                    waitingStep.type(), clock.instant()));

            // Block on approval gate
            var decision = waitForRecoveryApproval(execution.id());

            // Process decision
            synchronized (execution) {
                if (decision.approved()) {
                    execution.resumeStepFromApproval(waitingStep.id());
                    execution.resumeRunning();
                    executionRepository.save(execution);
                    eventPublisher.publish(new ExecutionEvent.StepApprovalDecided(
                            execution.id(), waitingStep.id(), true, null, clock.instant()));
                } else {
                    execution.skipStep(waitingStep.id(), "Rejected: " + decision.reason());
                    execution.resumeRunning();
                    executionRepository.save(execution);
                    eventPublisher.publish(new ExecutionEvent.StepApprovalDecided(
                            execution.id(), waitingStep.id(), false,
                            decision.reason(), clock.instant()));
                }
            }

            // Execute remaining steps
            executeRemainingSteps(execution, decision, waitingStep);

        } catch (Exception e) {
            log.error("Failed to recover execution {}: {}", execution.id(), e.getMessage(), e);
            failRecoveredExecution(execution, "Recovery failed: " + e.getMessage());
        }
    }

    private ApprovalDecision waitForRecoveryApproval(ExecutionId executionId) {
        try {
            var gate = approvalGateService.createGate(executionId);
            return gate.get(approvalTimeoutMinutes, TimeUnit.MINUTES);
        } catch (TimeoutException e) {
            log.warn("Recovery approval timeout for execution {}, auto-approving", executionId);
            approvalGateService.cancel(executionId);
            return ApprovalDecision.APPROVED;
        } catch (Exception e) {
            log.warn("Recovery approval error for execution {}, auto-approving: {}",
                    executionId, e.getMessage());
            approvalGateService.cancel(executionId);
            return ApprovalDecision.APPROVED;
        }
    }

    private void executeRemainingSteps(Execution execution, ApprovalDecision decision,
                                        ExecutionStep waitingStep) {
        var task = taskRepository.findById(execution.taskId()).orElseThrow();
        var taskDescription = task.description();

        var remainingSteps = new ArrayList<TaskPlannerPort.PlannedStep>();
        String recoveredStepName = null;

        if (decision.approved()) {
            remainingSteps.add(new TaskPlannerPort.PlannedStep(
                    waitingStep.name(), waitingStep.type()));
            recoveredStepName = waitingStep.name();
        }

        for (var step : execution.pendingSteps()) {
            remainingSteps.add(new TaskPlannerPort.PlannedStep(step.name(), step.type()));
        }

        if (remainingSteps.isEmpty()) {
            completeRecoveredExecution(execution);
            return;
        }

        var listener = ExecutionProgressListener.forRecovery(
                execution, executionRepository, eventPublisher,
                approvalGateService, clock, approvalEnabled,
                approvalTimeoutMinutes, recoveredStepName);

        try {
            graphOrchestrator.executeSteps(execution.id(), taskDescription,
                    remainingSteps, listener);
            completeRecoveredExecution(execution);
        } catch (Exception e) {
            failRecoveredExecution(execution, e.getMessage());
        }
    }

    private void completeRecoveredExecution(Execution execution) {
        var now = clock.instant();
        execution.markCompleted(now);
        tx.executeWithoutResult(status -> {
            executionRepository.save(execution);
            taskRepository.findById(execution.taskId()).ifPresent(task -> {
                task.markCompleted(now);
                taskRepository.save(task);
            });
        });
        eventPublisher.publish(new ExecutionEvent.ExecutionCompleted(execution.id(), now));
        log.info("Recovered execution {} completed", execution.id());
    }

    private void failRecoveredExecution(Execution execution, String reason) {
        approvalGateService.cancel(execution.id());
        var now = clock.instant();
        execution.markFailed(now);
        tx.executeWithoutResult(status -> {
            executionRepository.save(execution);
            taskRepository.findById(execution.taskId()).ifPresent(task -> {
                task.markFailed(now);
                taskRepository.save(task);
            });
        });
        graphOrchestrator.releaseCheckpoints(execution.id());
        eventPublisher.publish(new ExecutionEvent.ExecutionFailed(execution.id(), reason, now));
    }
}
