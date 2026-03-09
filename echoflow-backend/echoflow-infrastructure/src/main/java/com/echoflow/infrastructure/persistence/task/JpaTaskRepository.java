package com.echoflow.infrastructure.persistence.task;

import com.echoflow.domain.task.*;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class JpaTaskRepository implements TaskRepository {

    private final TaskJpaRepository jpa;

    public JpaTaskRepository(TaskJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public void save(Task task) {
        var existing = jpa.findById(task.id().value());
        if (existing.isPresent()) {
            var entity = existing.get();
            entity.setStatus(task.status().name());
            entity.setCompletedAt(task.completedAt());
            jpa.save(entity);
        } else {
            jpa.save(toEntity(task));
        }
    }

    @Override
    public Optional<Task> findById(TaskId id) {
        return jpa.findById(id.value()).map(this::toDomain);
    }

    @Override
    public List<Task> findAll() {
        return jpa.findAll().stream().map(this::toDomain).toList();
    }

    private TaskEntity toEntity(Task task) {
        return new TaskEntity(
                task.id().value(),
                task.description(),
                task.status().name(),
                task.createdAt(),
                task.completedAt()
        );
    }

    private Task toDomain(TaskEntity e) {
        return Task.reconstitute(
                new TaskId(e.getId()),
                e.getDescription(),
                TaskStatus.valueOf(e.getStatus()),
                e.getCreatedAt(),
                e.getCompletedAt()
        );
    }
}
