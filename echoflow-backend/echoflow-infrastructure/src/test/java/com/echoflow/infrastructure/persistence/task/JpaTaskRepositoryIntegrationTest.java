package com.echoflow.infrastructure.persistence.task;

import com.echoflow.domain.task.Task;
import com.echoflow.domain.task.TaskId;
import com.echoflow.domain.task.TaskStatus;
import com.echoflow.infrastructure.persistence.AbstractPostgresIntegrationTest;
import jakarta.persistence.OptimisticLockException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(JpaTaskRepository.class)
class JpaTaskRepositoryIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-03-10T12:00:00Z");
    private static final Instant LATER = Instant.parse("2026-03-10T12:30:00Z");

    @Autowired
    private JpaTaskRepository taskRepository;

    @Autowired
    private TestEntityManager entityManager;

    @BeforeEach
    void setUp() {
        entityManager.getEntityManager().createQuery("DELETE FROM TaskEntity").executeUpdate();
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    void save_and_findById_roundTrips_task() {
        var id = TaskId.generate();
        var task = Task.submit(id, "Research Java 21 features", NOW);

        taskRepository.save(task);
        entityManager.flush();
        entityManager.clear();

        var found = taskRepository.findById(id);

        assertThat(found).isPresent();
        var loaded = found.get();
        assertThat(loaded.id()).isEqualTo(id);
        assertThat(loaded.description()).isEqualTo("Research Java 21 features");
        assertThat(loaded.status()).isEqualTo(TaskStatus.SUBMITTED);
        assertThat(loaded.createdAt()).isEqualTo(NOW);
        assertThat(loaded.completedAt()).isNull();
    }

    @Test
    void save_persists_new_task() {
        var id = TaskId.generate();
        var task = Task.submit(id, "Write unit tests", NOW);

        taskRepository.save(task);
        entityManager.flush();

        var entity = entityManager.find(TaskEntity.class, id.value());
        assertThat(entity).isNotNull();
        assertThat(entity.getDescription()).isEqualTo("Write unit tests");
        assertThat(entity.getStatus()).isEqualTo("SUBMITTED");
    }

    @Test
    void findById_returns_empty_for_nonexistent_id() {
        var result = taskRepository.findById(TaskId.generate());

        assertThat(result).isEmpty();
    }

    @Test
    void findAll_returns_all_saved_tasks() {
        taskRepository.save(Task.submit(TaskId.generate(), "Task 1", NOW));
        taskRepository.save(Task.submit(TaskId.generate(), "Task 2", NOW));
        taskRepository.save(Task.submit(TaskId.generate(), "Task 3", NOW));
        entityManager.flush();
        entityManager.clear();

        var all = taskRepository.findAll();

        assertThat(all).hasSize(3);
        assertThat(all).extracting(Task::description)
                .containsExactlyInAnyOrder("Task 1", "Task 2", "Task 3");
    }

    @Test
    void findAll_returns_empty_when_no_tasks() {
        var all = taskRepository.findAll();

        assertThat(all).isEmpty();
    }

    @Test
    void save_updates_existing_task_status() {
        var id = TaskId.generate();
        var task = Task.submit(id, "Build feature", NOW);
        taskRepository.save(task);
        entityManager.flush();
        entityManager.clear();

        var loaded = taskRepository.findById(id).orElseThrow();
        loaded.markExecuting();
        taskRepository.save(loaded);
        entityManager.flush();
        entityManager.clear();

        var updated = taskRepository.findById(id).orElseThrow();
        assertThat(updated.status()).isEqualTo(TaskStatus.EXECUTING);
    }

    @Test
    void save_updates_completedAt_on_completion() {
        var id = TaskId.generate();
        var task = Task.submit(id, "Complete this", NOW);
        taskRepository.save(task);
        entityManager.flush();
        entityManager.clear();

        var loaded = taskRepository.findById(id).orElseThrow();
        loaded.markExecuting();
        taskRepository.save(loaded);
        entityManager.flush();
        entityManager.clear();

        var executing = taskRepository.findById(id).orElseThrow();
        executing.markCompleted(LATER);
        taskRepository.save(executing);
        entityManager.flush();
        entityManager.clear();

        var completed = taskRepository.findById(id).orElseThrow();
        assertThat(completed.status()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(completed.completedAt()).isEqualTo(LATER);
    }

    @Test
    void save_and_findById_preserves_unicode_description() {
        var id = TaskId.generate();
        var task = Task.submit(id, "调研 Java 21 虚拟线程特性", NOW);

        taskRepository.save(task);
        entityManager.flush();
        entityManager.clear();

        var loaded = taskRepository.findById(id).orElseThrow();
        assertThat(loaded.description()).isEqualTo("调研 Java 21 虚拟线程特性");
    }

    @Test
    void roundTrip_preserves_all_task_states() {
        var submittedId = TaskId.generate();
        taskRepository.save(Task.submit(submittedId, "submitted", NOW));

        var executingId = TaskId.generate();
        var executingTask = Task.submit(executingId, "executing", NOW);
        executingTask.markExecuting();
        taskRepository.save(executingTask);

        var completedId = TaskId.generate();
        var completedTask = Task.submit(completedId, "completed", NOW);
        completedTask.markExecuting();
        completedTask.markCompleted(LATER);
        taskRepository.save(completedTask);

        var failedId = TaskId.generate();
        var failedTask = Task.submit(failedId, "failed", NOW);
        failedTask.markExecuting();
        failedTask.markFailed(LATER);
        taskRepository.save(failedTask);

        entityManager.flush();
        entityManager.clear();

        assertThat(taskRepository.findById(submittedId).orElseThrow().status()).isEqualTo(TaskStatus.SUBMITTED);
        assertThat(taskRepository.findById(executingId).orElseThrow().status()).isEqualTo(TaskStatus.EXECUTING);
        assertThat(taskRepository.findById(completedId).orElseThrow().status()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(taskRepository.findById(failedId).orElseThrow().status()).isEqualTo(TaskStatus.FAILED);
    }

    @Test
    void concurrent_save_throws_optimistic_lock_exception() {
        var id = UUID.randomUUID();
        var entity = new TaskEntity(id, "test", "SUBMITTED", NOW, null);
        entityManager.persistAndFlush(entity);
        entityManager.detach(entity);

        var fresh = entityManager.find(TaskEntity.class, id);
        fresh.setStatus("EXECUTING");
        entityManager.flush();

        entity.setStatus("FAILED");
        assertThatThrownBy(() -> {
            entityManager.merge(entity);
            entityManager.flush();
        }).isInstanceOf(OptimisticLockException.class);
    }
}
