package com.echoflow.application.execution;

import com.echoflow.domain.EntityNotFoundException;
import com.echoflow.domain.execution.*;
import com.echoflow.domain.task.TaskId;
import com.echoflow.domain.task.TaskRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.Instant;

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

    private final TaskRepository taskRepository;
    private final ExecutionRepository executionRepository;
    private final ExecutionEventPublisher eventPublisher;
    private final TaskPlannerPort taskPlanner;
    private final GraphOrchestrationPort graphOrchestrator;
    private final Clock clock;
    private final TransactionOperations tx;

    public ExecuteTaskUseCase(TaskRepository taskRepository,
                              ExecutionRepository executionRepository,
                              ExecutionEventPublisher eventPublisher,
                              TaskPlannerPort taskPlanner,
                              GraphOrchestrationPort graphOrchestrator,
                              Clock clock,
                              TransactionOperations tx) {
        this.taskRepository = taskRepository;
        this.executionRepository = executionRepository;
        this.eventPublisher = eventPublisher;
        this.taskPlanner = taskPlanner;
        this.graphOrchestrator = graphOrchestrator;
        this.clock = clock;
        this.tx = tx;
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
     * StateGraph. The {@link GraphOrchestrationPort.StepProgressListener} callback
     * performs domain model updates and event publishing at each step boundary.</p>
     *
     * <p>When RESEARCH steps run in parallel, callbacks fire from multiple threads.
     * All callback bodies synchronize on {@code execution} to serialize domain
     * model mutations and database saves, preventing race conditions.</p>
     */
    private void runExecution(Execution execution) {
        var task = taskRepository.findById(execution.taskId())
                .orElseThrow(() -> new EntityNotFoundException("Task", execution.taskId()));
        var taskDescription = task.description();

        // Reconstruct planned steps from execution for the graph
        var plannedSteps = execution.steps().stream()
                .map(s -> new TaskPlannerPort.PlannedStep(s.name(), s.type()))
                .toList();

        try {
            graphOrchestrator.executeSteps(taskDescription, plannedSteps,
                    new GraphOrchestrationPort.StepProgressListener() {

                        @Override
                        public void onStepStarting(String stepName, StepType stepType) {
                            synchronized (execution) {
                                var step = execution.startStepByName(stepName);
                                eventPublisher.publish(new ExecutionEvent.StepStarted(
                                        execution.id(), step.id(), step.name(), clock.instant()));
                                appendLog(execution, step.id(), LogType.ACTION,
                                        "Executing: " + step.name(), clock.instant());
                            }
                        }

                        @Override
                        public void onStepCompleted(String stepName, String output) {
                            synchronized (execution) {
                                var step = findStepByName(execution, stepName);
                                appendLog(execution, step.id(), LogType.OBSERVATION,
                                        output, clock.instant());
                                completeStep(execution, step.id(), output);
                            }
                        }

                        @Override
                        public void onStepSkipped(String stepName, String reason) {
                            synchronized (execution) {
                                var step = findStepByName(execution, stepName);
                                appendLog(execution, step.id(), LogType.ERROR,
                                        "Step degraded: " + reason, clock.instant());
                                skipStep(execution, step.id(), reason);
                            }
                        }

                        @Override
                        public void onStepFailed(String stepName, String reason) {
                            synchronized (execution) {
                                var step = findStepByName(execution, stepName);
                                appendLog(execution, step.id(), LogType.ERROR,
                                        reason, clock.instant());
                                failStep(execution, step.id(), reason);
                            }
                        }
                    });

            completeExecution(execution);
        } catch (Exception e) {
            failExecution(execution, e.getMessage());
        }
    }

    private ExecutionStep findStepByName(Execution execution, String stepName) {
        return execution.steps().stream()
                .filter(s -> s.name().equals(stepName))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Step '" + stepName + "' not found in execution " + execution.id()));
    }

    private void appendLog(Execution execution, StepId stepId, LogType type, String content, Instant now) {
        var log = new StepLog(type, content, now);
        execution.appendStepLog(stepId, log);
        saveExecution(execution);
        eventPublisher.publish(new ExecutionEvent.StepLogAppended(
                execution.id(), stepId, type, content, now));
    }

    private void completeStep(Execution execution, StepId stepId, String output) {
        execution.completeStep(stepId, output);
        executionRepository.save(execution);
        eventPublisher.publish(new ExecutionEvent.StepCompleted(
                execution.id(), stepId, output, clock.instant()));
    }

    private void failStep(Execution execution, StepId stepId, String reason) {
        execution.failStep(stepId, reason);
        executionRepository.save(execution);
        eventPublisher.publish(new ExecutionEvent.StepFailed(
                execution.id(), stepId, reason, clock.instant()));
    }

    private void skipStep(Execution execution, StepId stepId, String reason) {
        execution.skipStep(stepId, reason);
        executionRepository.save(execution);
        eventPublisher.publish(new ExecutionEvent.StepSkipped(
                execution.id(), stepId, reason, clock.instant()));
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

    private void failExecution(Execution execution, String reason) {
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

    private void saveExecution(Execution execution) {
        executionRepository.save(execution);
    }
}
