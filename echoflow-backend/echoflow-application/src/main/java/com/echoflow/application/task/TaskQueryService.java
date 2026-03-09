package com.echoflow.application.task;

import com.echoflow.domain.EntityNotFoundException;
import com.echoflow.domain.execution.ExecutionRepository;
import com.echoflow.domain.task.TaskId;
import com.echoflow.domain.task.TaskRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Query service for tasks and their execution details.
 */
@Service
@Transactional(readOnly = true)
public class TaskQueryService {

    private final TaskRepository taskRepository;
    private final ExecutionRepository executionRepository;

    public TaskQueryService(TaskRepository taskRepository, ExecutionRepository executionRepository) {
        this.taskRepository = taskRepository;
        this.executionRepository = executionRepository;
    }

    public List<TaskResult> findAll() {
        return taskRepository.findAll().stream()
                .map(t -> new TaskResult(t.id(), t.description(), t.status(), t.createdAt(), t.completedAt()))
                .toList();
    }

    public TaskDetailResult findDetail(TaskId taskId) {
        var task = taskRepository.findById(taskId)
                .orElseThrow(() -> new EntityNotFoundException("Task", taskId));

        var executionOpt = executionRepository.findByTaskId(taskId);

        TaskDetailResult.ExecutionSnapshot execSnapshot = executionOpt.map(exec ->
                new TaskDetailResult.ExecutionSnapshot(
                        exec.id(),
                        exec.status(),
                        exec.startedAt(),
                        exec.completedAt(),
                        exec.steps().stream().map(step ->
                                new TaskDetailResult.StepSnapshot(
                                        step.id(),
                                        step.order(),
                                        step.name(),
                                        step.type(),
                                        step.status(),
                                        step.output(),
                                        step.logs().stream().map(log ->
                                                new TaskDetailResult.LogSnapshot(log.type(), log.content(), log.loggedAt())
                                        ).toList()
                                )
                        ).toList()
                )
        ).orElse(null);

        return new TaskDetailResult(
                task.id(),
                task.description(),
                task.status().name(),
                task.createdAt(),
                task.completedAt(),
                execSnapshot
        );
    }
}
