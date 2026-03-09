package com.echoflow.application.task;

import com.echoflow.domain.task.Task;
import com.echoflow.domain.task.TaskId;
import com.echoflow.domain.task.TaskRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * Use case: user submits a new async task.
 *
 * <p>Creates the Task aggregate and persists it. Execution is triggered
 * asynchronously by the caller (Web layer) after commit.</p>
 */
@Service
public class SubmitTaskUseCase {

    private final TaskRepository taskRepository;
    private final Clock clock;

    public SubmitTaskUseCase(TaskRepository taskRepository, Clock clock) {
        this.taskRepository = taskRepository;
        this.clock = clock;
    }

    @Transactional
    public TaskResult execute(SubmitTaskCommand command) {
        var id = TaskId.generate();
        var now = clock.instant();
        var task = Task.submit(id, command.description(), now);
        taskRepository.save(task);
        return new TaskResult(task.id(), task.description(), task.status(), task.createdAt(), task.completedAt());
    }
}
