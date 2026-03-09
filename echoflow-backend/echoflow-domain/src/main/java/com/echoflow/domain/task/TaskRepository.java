package com.echoflow.domain.task;

import java.util.List;
import java.util.Optional;

/**
 * Repository port for the {@link Task} aggregate.
 * Implementation lives in Infrastructure.
 */
public interface TaskRepository {

    void save(Task task);

    Optional<Task> findById(TaskId id);

    List<Task> findAll();
}
