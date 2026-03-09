package com.echoflow.application.execution;

import com.echoflow.domain.EntityNotFoundException;
import com.echoflow.domain.execution.*;
import com.echoflow.domain.task.TaskId;
import com.echoflow.domain.task.TaskRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Use case: execute a submitted task through a pipeline of steps.
 *
 * <p>Steps are planned by an LLM via {@link TaskPlannerPort}, then each step
 * is executed by the {@link StepExecutorPort} (which may call LLM, tools, etc.).</p>
 */
@Service
public class ExecuteTaskUseCase {

    private final TaskRepository taskRepository;
    private final ExecutionRepository executionRepository;
    private final ExecutionEventPublisher eventPublisher;
    private final TaskPlannerPort taskPlanner;
    private final StepExecutorPort stepExecutor;
    private final Clock clock;

    public ExecuteTaskUseCase(TaskRepository taskRepository,
                              ExecutionRepository executionRepository,
                              ExecutionEventPublisher eventPublisher,
                              TaskPlannerPort taskPlanner,
                              StepExecutorPort stepExecutor,
                              Clock clock) {
        this.taskRepository = taskRepository;
        this.executionRepository = executionRepository;
        this.eventPublisher = eventPublisher;
        this.taskPlanner = taskPlanner;
        this.stepExecutor = stepExecutor;
        this.clock = clock;
    }

    /**
     * Plan and execute the task. Designed to be called asynchronously
     * (e.g. on a virtual thread) after the task is committed.
     */
    public void execute(TaskId taskId) {
        var execution = planExecution(taskId);
        runExecution(execution);
    }

    @Transactional
    protected Execution planExecution(TaskId taskId) {
        var task = taskRepository.findById(taskId)
                .orElseThrow(() -> new EntityNotFoundException("Task", taskId));

        // Call LLM to decompose the task (outside transaction is ideal,
        // but for simplicity we do it here; the call is fast and idempotent)
        var plannedSteps = taskPlanner.planSteps(task.description());

        if (plannedSteps.isEmpty()) {
            throw new TaskPlanningException("LLM returned zero steps for task: " + taskId);
        }

        task.markExecuting();
        taskRepository.save(task);

        var now = clock.instant();
        var execution = Execution.create(ExecutionId.generate(), taskId, now);

        for (var step : plannedSteps) {
            execution.addStep(StepId.generate(), step.name(), step.type(), now);
        }

        execution.startRunning();
        executionRepository.save(execution);

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

    private void runExecution(Execution execution) {
        var task = taskRepository.findById(execution.taskId())
                .orElseThrow(() -> new EntityNotFoundException("Task", execution.taskId()));
        var taskDescription = task.description();
        var previousOutputs = new ArrayList<String>();

        try {
            while (execution.hasPendingSteps()) {
                var step = execution.startNextStep();
                var now = clock.instant();

                eventPublisher.publish(new ExecutionEvent.StepStarted(
                        execution.id(), step.id(), step.name(), now));

                try {
                    var context = new StepExecutionContext(
                            taskDescription, step.name(), step.type(),
                            List.copyOf(previousOutputs));

                    appendLog(execution, step.id(), LogType.ACTION,
                            "Executing: " + step.name(), now);

                    var result = stepExecutor.execute(context);

                    appendLog(execution, step.id(), LogType.OBSERVATION,
                            result.output(), clock.instant());

                    completeStep(execution, step.id(), result.output());
                    previousOutputs.add(result.output());
                } catch (Exception e) {
                    appendLog(execution, step.id(), LogType.ERROR,
                            e.getMessage(), clock.instant());
                    failStep(execution, step.id(), e.getMessage());
                    failExecution(execution, e.getMessage());
                    return;
                }
            }

            completeExecution(execution);
        } catch (Exception e) {
            failExecution(execution, e.getMessage());
        }
    }

    private void appendLog(Execution execution, StepId stepId, LogType type, String content, Instant now) {
        var log = new StepLog(type, content, now);
        execution.appendStepLog(stepId, log);
        saveExecution(execution);
        eventPublisher.publish(new ExecutionEvent.StepLogAppended(
                execution.id(), stepId, type, content, now));
    }

    @Transactional
    protected void completeStep(Execution execution, StepId stepId, String output) {
        execution.completeStep(stepId, output);
        executionRepository.save(execution);
        eventPublisher.publish(new ExecutionEvent.StepCompleted(
                execution.id(), stepId, output, clock.instant()));
    }

    @Transactional
    protected void failStep(Execution execution, StepId stepId, String reason) {
        execution.failStep(stepId, reason);
        executionRepository.save(execution);
        eventPublisher.publish(new ExecutionEvent.StepFailed(
                execution.id(), stepId, reason, clock.instant()));
    }

    @Transactional
    protected void completeExecution(Execution execution) {
        var now = clock.instant();
        execution.markCompleted(now);
        executionRepository.save(execution);

        var taskId = execution.taskId();
        var task = taskRepository.findById(taskId)
                .orElseThrow(() -> new EntityNotFoundException("Task", taskId));
        task.markCompleted(now);
        taskRepository.save(task);

        eventPublisher.publish(new ExecutionEvent.ExecutionCompleted(execution.id(), now));
    }

    @Transactional
    protected void failExecution(Execution execution, String reason) {
        var now = clock.instant();
        execution.markFailed(now);
        executionRepository.save(execution);

        var taskId = execution.taskId();
        taskRepository.findById(taskId).ifPresent(task -> {
            task.markFailed(now);
            taskRepository.save(task);
        });

        eventPublisher.publish(new ExecutionEvent.ExecutionFailed(execution.id(), reason, now));
    }

    @Transactional
    protected void saveExecution(Execution execution) {
        executionRepository.save(execution);
    }
}
