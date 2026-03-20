package com.echoflow.domain.execution;

import com.echoflow.domain.task.TaskId;

import java.util.List;
import java.util.Optional;

/**
 * Repository port for the {@link Execution} aggregate.
 * Implementation lives in Infrastructure.
 */
public interface ExecutionRepository {

    void save(Execution execution);

    Optional<Execution> findById(ExecutionId id);

    Optional<Execution> findByTaskId(TaskId taskId);

    List<Execution> findByStatus(ExecutionStatus status);
}
