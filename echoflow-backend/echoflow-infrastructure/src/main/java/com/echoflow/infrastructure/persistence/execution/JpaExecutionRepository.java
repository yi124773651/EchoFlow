package com.echoflow.infrastructure.persistence.execution;

import com.echoflow.domain.execution.*;
import com.echoflow.domain.task.TaskId;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JpaExecutionRepository implements ExecutionRepository {

    private final ExecutionJpaRepository jpa;

    public JpaExecutionRepository(ExecutionJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public void save(Execution execution) {
        var existing = jpa.findById(execution.id().value());
        if (existing.isPresent()) {
            updateEntity(existing.get(), execution);
        } else {
            jpa.save(toEntity(execution));
        }
    }

    @Override
    public Optional<Execution> findById(ExecutionId id) {
        return jpa.findById(id.value()).map(this::toDomain);
    }

    @Override
    public Optional<Execution> findByTaskId(TaskId taskId) {
        return jpa.findByTaskId(taskId.value()).map(this::toDomain);
    }

    private ExecutionEntity toEntity(Execution exec) {
        var entity = new ExecutionEntity(
                exec.id().value(),
                exec.taskId().value(),
                exec.status().name(),
                exec.startedAt(),
                exec.completedAt()
        );
        entity.setSteps(exec.steps().stream().map(step -> toStepEntity(exec.id().value(), step)).toList());
        return entity;
    }

    private ExecutionStepEntity toStepEntity(UUID executionId, ExecutionStep step) {
        var entity = new ExecutionStepEntity(
                step.id().value(),
                executionId,
                step.order(),
                step.name(),
                step.type().name(),
                step.status().name(),
                step.output(),
                step.createdAt()
        );
        entity.setLogs(step.logs().stream().map(log -> new StepLogEntity(
                UUID.randomUUID(),
                entity,
                log.type().name(),
                log.content(),
                log.loggedAt()
        )).toList());
        return entity;
    }

    private void updateEntity(ExecutionEntity entity, Execution exec) {
        entity.setStatus(exec.status().name());
        entity.setCompletedAt(exec.completedAt());

        // Clear and rebuild steps (within same transaction)
        entity.getSteps().clear();
        entity.getSteps().addAll(
                exec.steps().stream().map(step -> toStepEntity(exec.id().value(), step)).toList()
        );
        jpa.save(entity);
    }

    private Execution toDomain(ExecutionEntity e) {
        var steps = e.getSteps().stream().map(this::toStepDomain).toList();
        return Execution.reconstitute(
                new ExecutionId(e.getId()),
                new TaskId(e.getTaskId()),
                ExecutionStatus.valueOf(e.getStatus()),
                e.getStartedAt(),
                e.getCompletedAt(),
                steps
        );
    }

    private ExecutionStep toStepDomain(ExecutionStepEntity e) {
        var logs = e.getLogs().stream()
                .map(l -> new StepLog(LogType.valueOf(l.getType()), l.getContent(), l.getLoggedAt()))
                .toList();
        return ExecutionStep.reconstitute(
                new StepId(e.getId()),
                e.getStepOrder(),
                e.getName(),
                StepType.valueOf(e.getType()),
                StepStatus.valueOf(e.getStatus()),
                e.getOutput(),
                e.getCreatedAt(),
                logs
        );
    }
}
