package com.echoflow.application.execution;

import com.echoflow.domain.execution.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Reusable {@link GraphOrchestrationPort.StepProgressListener} that performs
 * domain model updates and publishes SSE events during graph execution.
 *
 * <p>Supports both normal execution and recovery mode. In recovery mode,
 * the recovered step is already in RUNNING status (resumed from WAITING_APPROVAL),
 * so {@link #onStepStarting} skips the PENDING→RUNNING transition for that step.</p>
 *
 * <p>All callback methods synchronize on the {@code execution} aggregate to
 * serialize domain model mutations and database saves when callbacks fire
 * from parallel threads (e.g., parallel RESEARCH steps).</p>
 */
class ExecutionProgressListener implements GraphOrchestrationPort.StepProgressListener {

    private static final Logger log = LoggerFactory.getLogger(ExecutionProgressListener.class);

    private final Execution execution;
    private final ExecutionRepository executionRepository;
    private final ExecutionEventPublisher eventPublisher;
    private final ApprovalGateService approvalGateService;
    private final Clock clock;
    private final boolean approvalEnabled;
    private final int approvalTimeoutMinutes;
    private final String recoveredStepName;

    ExecutionProgressListener(Execution execution,
                              ExecutionRepository executionRepository,
                              ExecutionEventPublisher eventPublisher,
                              ApprovalGateService approvalGateService,
                              Clock clock,
                              boolean approvalEnabled,
                              int approvalTimeoutMinutes,
                              String recoveredStepName) {
        this.execution = execution;
        this.executionRepository = executionRepository;
        this.eventPublisher = eventPublisher;
        this.approvalGateService = approvalGateService;
        this.clock = clock;
        this.approvalEnabled = approvalEnabled;
        this.approvalTimeoutMinutes = approvalTimeoutMinutes;
        this.recoveredStepName = recoveredStepName;
    }

    /**
     * Create a listener for normal (non-recovery) execution.
     */
    static ExecutionProgressListener forNormalExecution(Execution execution,
                                                        ExecutionRepository executionRepository,
                                                        ExecutionEventPublisher eventPublisher,
                                                        ApprovalGateService approvalGateService,
                                                        Clock clock,
                                                        boolean approvalEnabled,
                                                        int approvalTimeoutMinutes) {
        return new ExecutionProgressListener(execution, executionRepository, eventPublisher,
                approvalGateService, clock, approvalEnabled, approvalTimeoutMinutes, null);
    }

    /**
     * Create a listener for recovery execution. The {@code recoveredStepName}
     * identifies the step that was already resumed from WAITING_APPROVAL — its
     * PENDING→RUNNING transition is skipped.
     */
    static ExecutionProgressListener forRecovery(Execution execution,
                                                  ExecutionRepository executionRepository,
                                                  ExecutionEventPublisher eventPublisher,
                                                  ApprovalGateService approvalGateService,
                                                  Clock clock,
                                                  boolean approvalEnabled,
                                                  int approvalTimeoutMinutes,
                                                  String recoveredStepName) {
        return new ExecutionProgressListener(execution, executionRepository, eventPublisher,
                approvalGateService, clock, approvalEnabled, approvalTimeoutMinutes, recoveredStepName);
    }

    @Override
    public void onStepStarting(String stepName, StepType stepType) {
        synchronized (execution) {
            if (stepName.equals(recoveredStepName)) {
                // Recovery mode: step is already RUNNING (resumed from WAITING_APPROVAL).
                // Publish event so frontend is aware, but skip domain state transition.
                var step = findStepByName(stepName);
                eventPublisher.publish(new ExecutionEvent.StepStarted(
                        execution.id(), step.id(), step.name(), clock.instant()));
                appendLog(step.id(), LogType.ACTION,
                        "Resuming after recovery: " + step.name());
                return;
            }
            var step = execution.startStepByName(stepName);
            eventPublisher.publish(new ExecutionEvent.StepStarted(
                    execution.id(), step.id(), step.name(), clock.instant()));
            appendLog(step.id(), LogType.ACTION,
                    "Executing: " + step.name());
        }
    }

    @Override
    public void onStepCompleted(String stepName, String output) {
        synchronized (execution) {
            var step = findStepByName(stepName);
            appendLog(step.id(), LogType.OBSERVATION, output);
            execution.completeStep(step.id(), output);
            executionRepository.save(execution);
            eventPublisher.publish(new ExecutionEvent.StepCompleted(
                    execution.id(), step.id(), output, clock.instant()));
        }
    }

    @Override
    public void onStepSkipped(String stepName, String reason) {
        synchronized (execution) {
            var step = findStepByName(stepName);
            appendLog(step.id(), LogType.ERROR, "Step degraded: " + reason);
            execution.skipStep(step.id(), reason);
            executionRepository.save(execution);
            eventPublisher.publish(new ExecutionEvent.StepSkipped(
                    execution.id(), step.id(), reason, clock.instant()));
        }
    }

    @Override
    public void onStepFailed(String stepName, String reason) {
        synchronized (execution) {
            var step = findStepByName(stepName);
            appendLog(step.id(), LogType.ERROR, reason);
            execution.failStep(step.id(), reason);
            executionRepository.save(execution);
            eventPublisher.publish(new ExecutionEvent.StepFailed(
                    execution.id(), step.id(), reason, clock.instant()));
        }
    }

    @Override
    public void onStepProgress(String stepName, LogType logType, String content) {
        synchronized (execution) {
            var step = findStepByName(stepName);
            appendLog(step.id(), logType, content);
        }
    }

    @Override
    public ApprovalDecision onStepAwaitingApproval(String stepName, StepType stepType) {
        if (!approvalEnabled || stepType != StepType.WRITE) {
            return ApprovalDecision.APPROVED;
        }
        return waitForApproval(stepName);
    }

    Execution execution() {
        return execution;
    }

    // --- Private helpers ---

    private ApprovalDecision waitForApproval(String stepName) {
        synchronized (execution) {
            var step = findStepByName(stepName);
            execution.markStepWaitingApproval(step.id());
            execution.markWaitingApproval();
            executionRepository.save(execution);

            appendLog(step.id(), LogType.ACTION,
                    "Awaiting human approval");
            eventPublisher.publish(new ExecutionEvent.StepAwaitingApproval(
                    execution.id(), step.id(), stepName, step.type(), clock.instant()));
        }

        try {
            var gate = approvalGateService.createGate(execution.id());
            var decision = gate.get(approvalTimeoutMinutes, TimeUnit.MINUTES);

            synchronized (execution) {
                var step = findStepByName(stepName);
                execution.resumeStepFromApproval(step.id());
                execution.resumeRunning();
                executionRepository.save(execution);

                appendLog(step.id(), LogType.ACTION,
                        decision.approved()
                                ? "Approved by user"
                                : "Rejected by user: " + decision.reason());
                eventPublisher.publish(new ExecutionEvent.StepApprovalDecided(
                        execution.id(), step.id(), decision.approved(),
                        decision.reason(), clock.instant()));
            }
            return decision;
        } catch (TimeoutException e) {
            log.warn("Approval timeout for execution {}, auto-approving", execution.id());
            approvalGateService.cancel(execution.id());
            synchronized (execution) {
                var step = findStepByName(stepName);
                execution.resumeStepFromApproval(step.id());
                execution.resumeRunning();
                executionRepository.save(execution);
            }
            return ApprovalDecision.APPROVED;
        } catch (Exception e) {
            log.warn("Approval gate error for execution {}, auto-approving: {}",
                    execution.id(), e.getMessage());
            approvalGateService.cancel(execution.id());
            synchronized (execution) {
                var step = findStepByName(stepName);
                execution.resumeStepFromApproval(step.id());
                execution.resumeRunning();
                executionRepository.save(execution);
            }
            return ApprovalDecision.APPROVED;
        }
    }

    private ExecutionStep findStepByName(String stepName) {
        return execution.steps().stream()
                .filter(s -> s.name().equals(stepName))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Step '" + stepName + "' not found in execution " + execution.id()));
    }

    private void appendLog(StepId stepId, LogType type, String content) {
        var logEntry = new StepLog(type, content, clock.instant());
        execution.appendStepLog(stepId, logEntry);
        executionRepository.save(execution);
        eventPublisher.publish(new ExecutionEvent.StepLogAppended(
                execution.id(), stepId, type, content, clock.instant()));
    }
}
