package com.echoflow.application.execution;

import com.echoflow.domain.EntityNotFoundException;
import com.echoflow.domain.execution.*;
import com.echoflow.domain.task.TaskId;
import com.echoflow.domain.task.TaskRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;

/**
 * Use case: execute a submitted task through a pipeline of steps.
 *
 * <p>Steps are planned by an LLM via {@link TaskPlannerPort}, then executed
 * via {@link GraphOrchestrationPort} (StateGraph-driven chain with optional
 * parallel fan-out for RESEARCH steps). The
 * {@link GraphOrchestrationPort.StepProgressListener} callback ensures domain
 * model updates and SSE events happen at each step boundary.</p>
 *
 * <p>When RESEARCH steps execute in parallel, multiple callbacks may fire
 * concurrently from different threads. All callback methods synchronize on
 * the {@code execution} aggregate to serialize domain model mutations and
 * database saves.</p>
 *
 * <p>Transaction boundaries are managed programmatically via {@link TransactionOperations}
 * to ensure LLM/remote calls never execute inside a database transaction (Rule 4).</p>
 */
@Service
public class ExecuteTaskUseCase {

    private static final Logger log = LoggerFactory.getLogger(ExecuteTaskUseCase.class);

    private final TaskRepository taskRepository;
    private final ExecutionRepository executionRepository;
    private final ExecutionEventPublisher eventPublisher;
    private final TaskPlannerPort taskPlanner;
    private final GraphOrchestrationPort graphOrchestrator;
    private final ApprovalGateService approvalGateService;
    private final Clock clock;
    private final TransactionOperations tx;
    private final boolean approvalEnabled;
    private final int approvalTimeoutMinutes;

    public ExecuteTaskUseCase(TaskRepository taskRepository,
                              ExecutionRepository executionRepository,
                              ExecutionEventPublisher eventPublisher,
                              TaskPlannerPort taskPlanner,
                              GraphOrchestrationPort graphOrchestrator,
                              ApprovalGateService approvalGateService,
                              Clock clock,
                              TransactionOperations tx,
                              @Value("${echoflow.approval.enabled:false}") boolean approvalEnabled,
                              @Value("${echoflow.approval.timeout-minutes:30}") int approvalTimeoutMinutes) {
        this.taskRepository = taskRepository;
        this.executionRepository = executionRepository;
        this.eventPublisher = eventPublisher;
        this.taskPlanner = taskPlanner;
        this.graphOrchestrator = graphOrchestrator;
        this.approvalGateService = approvalGateService;
        this.clock = clock;
        this.tx = tx;
        this.approvalEnabled = approvalEnabled;
        this.approvalTimeoutMinutes = approvalTimeoutMinutes;
    }

    /**
     * Plan and execute the task. Designed to be called asynchronously
     * (e.g. on a virtual thread) after the task is committed.
     */
    public void execute(TaskId taskId) {
        var execution = planExecution(taskId);
        runExecution(execution);
    }

    /**
     * Phase 1: Load task, call LLM for step planning, then persist atomically.
     * LLM call is explicitly outside any database transaction.
     */
    Execution planExecution(TaskId taskId) {
        // Read task (implicit per-call tx via repository)
        var task = taskRepository.findById(taskId)
                .orElseThrow(() -> new EntityNotFoundException("Task", taskId));

        // LLM call — NO transaction
        var plannedSteps = taskPlanner.planSteps(task.description());

        if (plannedSteps.isEmpty()) {
            throw new TaskPlanningException("LLM returned zero steps for task: " + taskId);
        }

        // Prepare domain objects in memory
        var now = clock.instant();
        task.markExecuting();

        var execution = Execution.create(ExecutionId.generate(), taskId, now);
        for (var step : plannedSteps) {
            execution.addStep(StepId.generate(), step.name(), step.type(), now);
        }
        execution.startRunning();

        // Atomic write — short transaction
        tx.executeWithoutResult(status -> {
            taskRepository.save(task);
            executionRepository.save(execution);
        });

        // Publish event AFTER transaction commits
        eventPublisher.publish(new ExecutionEvent.ExecutionStarted(
                execution.id(), taskId,
                execution.steps().stream().map(s ->
                        new ExecutionEvent.ExecutionStarted.StepInfo(
                                s.id(), s.order(), s.name(), s.type().name())
                ).toList(),
                now
        ));

        return execution;
    }

    /**
     * Phase 2: Execute steps via StateGraph (linear or parallel fan-out).
     *
     * <p>Delegates to {@link GraphOrchestrationPort} which drives execution via
     * StateGraph. The {@link ExecutionProgressListener} callback performs domain
     * model updates and event publishing at each step boundary.</p>
     */
    private void runExecution(Execution execution) {
        var task = taskRepository.findById(execution.taskId())
                .orElseThrow(() -> new EntityNotFoundException("Task", execution.taskId()));
        var taskDescription = task.description();

        // Reconstruct planned steps from execution for the graph
        var plannedSteps = execution.steps().stream()
                .map(s -> new TaskPlannerPort.PlannedStep(s.name(), s.type()))
                .toList();

        var listener = ExecutionProgressListener.forNormalExecution(
                execution, executionRepository, eventPublisher,
                approvalGateService, clock, approvalEnabled, approvalTimeoutMinutes);

        try {
            graphOrchestrator.executeSteps(execution.id(), taskDescription,
                    plannedSteps, listener);
            completeExecution(execution);
        } catch (Exception e) {
            failExecution(execution, e.getMessage());
        }
    }

    private void completeExecution(Execution execution) {
        var now = clock.instant();
        execution.markCompleted(now);

        var taskId = execution.taskId();
        var task = taskRepository.findById(taskId)
                .orElseThrow(() -> new EntityNotFoundException("Task", taskId));
        task.markCompleted(now);

        tx.executeWithoutResult(status -> {
            executionRepository.save(execution);
            taskRepository.save(task);
        });

        eventPublisher.publish(new ExecutionEvent.ExecutionCompleted(execution.id(), now));
    }

    void failExecution(Execution execution, String reason) {
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

        eventPublisher.publish(new ExecutionEvent.ExecutionFailed(execution.id(), reason, now));
    }
}
