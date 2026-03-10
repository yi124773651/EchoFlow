package com.echoflow.infrastructure.persistence.execution;

import com.echoflow.domain.execution.*;
import com.echoflow.domain.task.Task;
import com.echoflow.domain.task.TaskId;
import com.echoflow.infrastructure.persistence.AbstractPostgresIntegrationTest;
import com.echoflow.infrastructure.persistence.task.JpaTaskRepository;
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

@Import({JpaExecutionRepository.class, JpaTaskRepository.class})
class JpaExecutionRepositoryIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-03-10T12:00:00Z");
    private static final Instant LATER = Instant.parse("2026-03-10T12:30:00Z");

    @Autowired
    private JpaExecutionRepository executionRepository;

    @Autowired
    private JpaTaskRepository taskRepository;

    @Autowired
    private TestEntityManager entityManager;

    private TaskId taskId;

    @BeforeEach
    void setUp() {
        entityManager.getEntityManager().createNativeQuery("DELETE FROM step_log").executeUpdate();
        entityManager.getEntityManager().createNativeQuery("DELETE FROM execution_step").executeUpdate();
        entityManager.getEntityManager().createNativeQuery("DELETE FROM execution").executeUpdate();
        entityManager.getEntityManager().createQuery("DELETE FROM TaskEntity").executeUpdate();
        entityManager.flush();
        entityManager.clear();

        taskId = TaskId.generate();
        taskRepository.save(Task.submit(taskId, "Test task", NOW));
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    void save_and_findById_roundTrips_execution() {
        var execId = ExecutionId.generate();
        var execution = Execution.create(execId, taskId, NOW);

        executionRepository.save(execution);
        entityManager.flush();
        entityManager.clear();

        var found = executionRepository.findById(execId);

        assertThat(found).isPresent();
        var loaded = found.get();
        assertThat(loaded.id()).isEqualTo(execId);
        assertThat(loaded.taskId()).isEqualTo(taskId);
        assertThat(loaded.status()).isEqualTo(ExecutionStatus.PLANNING);
        assertThat(loaded.startedAt()).isEqualTo(NOW);
        assertThat(loaded.completedAt()).isNull();
        assertThat(loaded.steps()).isEmpty();
    }

    @Test
    void findById_returns_empty_for_nonexistent_id() {
        var result = executionRepository.findById(ExecutionId.generate());

        assertThat(result).isEmpty();
    }

    @Test
    void findByTaskId_returns_execution() {
        var execId = ExecutionId.generate();
        executionRepository.save(Execution.create(execId, taskId, NOW));
        entityManager.flush();
        entityManager.clear();

        var found = executionRepository.findByTaskId(taskId);

        assertThat(found).isPresent();
        assertThat(found.get().id()).isEqualTo(execId);
    }

    @Test
    void findByTaskId_returns_empty_when_no_execution() {
        var otherTaskId = TaskId.generate();
        taskRepository.save(Task.submit(otherTaskId, "Other task", NOW));
        entityManager.flush();
        entityManager.clear();

        var result = executionRepository.findByTaskId(otherTaskId);

        assertThat(result).isEmpty();
    }

    @Test
    void save_and_findById_roundTrips_execution_with_steps() {
        var execId = ExecutionId.generate();
        var execution = Execution.create(execId, taskId, NOW);
        execution.addStep(StepId.generate(), "Analyze task", StepType.THINK, NOW);
        execution.addStep(StepId.generate(), "Search info", StepType.RESEARCH, NOW);
        execution.addStep(StepId.generate(), "Write result", StepType.WRITE, NOW);

        executionRepository.save(execution);
        entityManager.flush();
        entityManager.clear();

        var loaded = executionRepository.findById(execId).orElseThrow();

        assertThat(loaded.steps()).hasSize(3);
        assertThat(loaded.steps().get(0).name()).isEqualTo("Analyze task");
        assertThat(loaded.steps().get(0).type()).isEqualTo(StepType.THINK);
        assertThat(loaded.steps().get(0).status()).isEqualTo(StepStatus.PENDING);
        assertThat(loaded.steps().get(1).name()).isEqualTo("Search info");
        assertThat(loaded.steps().get(1).type()).isEqualTo(StepType.RESEARCH);
        assertThat(loaded.steps().get(2).name()).isEqualTo("Write result");
        assertThat(loaded.steps().get(2).type()).isEqualTo(StepType.WRITE);
    }

    @Test
    void roundTrip_preserves_step_order() {
        var execId = ExecutionId.generate();
        var execution = Execution.create(execId, taskId, NOW);
        execution.addStep(StepId.generate(), "Step A", StepType.THINK, NOW);
        execution.addStep(StepId.generate(), "Step B", StepType.RESEARCH, NOW);
        execution.addStep(StepId.generate(), "Step C", StepType.WRITE, NOW);
        execution.addStep(StepId.generate(), "Step D", StepType.NOTIFY, NOW);

        executionRepository.save(execution);
        entityManager.flush();
        entityManager.clear();

        var loaded = executionRepository.findById(execId).orElseThrow();

        assertThat(loaded.steps()).hasSize(4);
        assertThat(loaded.steps().get(0).order()).isEqualTo(1);
        assertThat(loaded.steps().get(1).order()).isEqualTo(2);
        assertThat(loaded.steps().get(2).order()).isEqualTo(3);
        assertThat(loaded.steps().get(3).order()).isEqualTo(4);
    }

    @Test
    void roundTrip_preserves_step_output() {
        var execId = ExecutionId.generate();
        var stepId = StepId.generate();
        var execution = Execution.create(execId, taskId, NOW);
        execution.addStep(stepId, "Think step", StepType.THINK, NOW);
        execution.startRunning();
        execution.startNextStep();
        execution.completeStep(stepId, "The analysis output text");

        executionRepository.save(execution);
        entityManager.flush();
        entityManager.clear();

        var loaded = executionRepository.findById(execId).orElseThrow();
        var step = loaded.steps().getFirst();
        assertThat(step.status()).isEqualTo(StepStatus.COMPLETED);
        assertThat(step.output()).isEqualTo("The analysis output text");
    }

    @Test
    void roundTrip_preserves_step_logs() {
        var execId = ExecutionId.generate();
        var stepId = StepId.generate();
        var execution = Execution.create(execId, taskId, NOW);
        execution.addStep(stepId, "Think step", StepType.THINK, NOW);
        execution.startRunning();
        execution.startNextStep();
        execution.appendStepLog(stepId, new StepLog(LogType.THOUGHT, "Analyzing the task...", NOW));
        execution.appendStepLog(stepId, new StepLog(LogType.ACTION, "Decided to break down into parts", LATER));

        executionRepository.save(execution);
        entityManager.flush();
        entityManager.clear();

        var loaded = executionRepository.findById(execId).orElseThrow();
        var logs = loaded.steps().getFirst().logs();
        assertThat(logs).hasSize(2);
        assertThat(logs.get(0).type()).isEqualTo(LogType.THOUGHT);
        assertThat(logs.get(0).content()).isEqualTo("Analyzing the task...");
        assertThat(logs.get(0).loggedAt()).isEqualTo(NOW);
        assertThat(logs.get(1).type()).isEqualTo(LogType.ACTION);
        assertThat(logs.get(1).content()).isEqualTo("Decided to break down into parts");
    }

    @Test
    void roundTrip_preserves_log_types() {
        var execId = ExecutionId.generate();
        var stepId = StepId.generate();
        var execution = Execution.create(execId, taskId, NOW);
        execution.addStep(stepId, "Think step", StepType.THINK, NOW);
        execution.startRunning();
        execution.startNextStep();
        execution.appendStepLog(stepId, new StepLog(LogType.THOUGHT, "thought content", NOW));
        execution.appendStepLog(stepId, new StepLog(LogType.ACTION, "action content", NOW));
        execution.appendStepLog(stepId, new StepLog(LogType.OBSERVATION, "observation content", NOW));
        execution.appendStepLog(stepId, new StepLog(LogType.ERROR, "error content", NOW));

        executionRepository.save(execution);
        entityManager.flush();
        entityManager.clear();

        var loaded = executionRepository.findById(execId).orElseThrow();
        var logs = loaded.steps().getFirst().logs();
        assertThat(logs).hasSize(4);
        assertThat(logs).extracting(StepLog::type)
                .containsExactly(LogType.THOUGHT, LogType.ACTION, LogType.OBSERVATION, LogType.ERROR);
    }

    @Test
    void save_updates_execution_status() {
        var execId = ExecutionId.generate();
        var execution = Execution.create(execId, taskId, NOW);
        execution.addStep(StepId.generate(), "Step 1", StepType.THINK, NOW);
        executionRepository.save(execution);
        entityManager.flush();
        entityManager.clear();

        var loaded = executionRepository.findById(execId).orElseThrow();
        loaded.startRunning();
        executionRepository.save(loaded);
        entityManager.flush();
        entityManager.clear();

        var updated = executionRepository.findById(execId).orElseThrow();
        assertThat(updated.status()).isEqualTo(ExecutionStatus.RUNNING);
    }

    @Test
    void save_rebuilds_steps_on_update() {
        var execId = ExecutionId.generate();
        var stepId1 = StepId.generate();
        var stepId2 = StepId.generate();
        var execution = Execution.create(execId, taskId, NOW);
        execution.addStep(stepId1, "Think", StepType.THINK, NOW);
        execution.addStep(stepId2, "Write", StepType.WRITE, NOW);
        executionRepository.save(execution);
        entityManager.flush();
        entityManager.clear();

        var loaded = executionRepository.findById(execId).orElseThrow();
        loaded.startRunning();
        loaded.startNextStep();
        loaded.appendStepLog(stepId1, new StepLog(LogType.THOUGHT, "thinking...", NOW));
        loaded.completeStep(stepId1, "thought output");
        executionRepository.save(loaded);
        entityManager.flush();
        entityManager.clear();

        var updated = executionRepository.findById(execId).orElseThrow();
        assertThat(updated.steps()).hasSize(2);
        assertThat(updated.steps().get(0).status()).isEqualTo(StepStatus.COMPLETED);
        assertThat(updated.steps().get(0).output()).isEqualTo("thought output");
        assertThat(updated.steps().get(0).logs()).hasSize(1);
        assertThat(updated.steps().get(0).logs().getFirst().content()).isEqualTo("thinking...");
        assertThat(updated.steps().get(1).status()).isEqualTo(StepStatus.PENDING);
    }

    @Test
    void roundTrip_preserves_completed_execution_with_all_step_states() {
        var execId = ExecutionId.generate();
        var thinkId = StepId.generate();
        var researchId = StepId.generate();
        var writeId = StepId.generate();
        var notifyId = StepId.generate();
        var execution = Execution.create(execId, taskId, NOW);
        execution.addStep(thinkId, "Think", StepType.THINK, NOW);
        execution.addStep(researchId, "Research", StepType.RESEARCH, NOW);
        execution.addStep(writeId, "Write", StepType.WRITE, NOW);
        execution.addStep(notifyId, "Notify", StepType.NOTIFY, NOW);
        execution.startRunning();

        execution.startNextStep();
        execution.completeStep(thinkId, "thought output");

        execution.startNextStep();
        execution.skipStep(researchId, "LLM timeout");

        execution.startNextStep();
        execution.completeStep(writeId, "write output");

        execution.startNextStep();
        execution.completeStep(notifyId, "notification sent");

        execution.markCompleted(LATER);

        executionRepository.save(execution);
        entityManager.flush();
        entityManager.clear();

        var loaded = executionRepository.findById(execId).orElseThrow();
        assertThat(loaded.status()).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(loaded.completedAt()).isEqualTo(LATER);
        assertThat(loaded.steps()).hasSize(4);
        assertThat(loaded.steps().get(0).status()).isEqualTo(StepStatus.COMPLETED);
        assertThat(loaded.steps().get(0).output()).isEqualTo("thought output");
        assertThat(loaded.steps().get(1).status()).isEqualTo(StepStatus.SKIPPED);
        assertThat(loaded.steps().get(1).output()).isEqualTo("LLM timeout");
        assertThat(loaded.steps().get(2).status()).isEqualTo(StepStatus.COMPLETED);
        assertThat(loaded.steps().get(3).status()).isEqualTo(StepStatus.COMPLETED);
    }

    @Test
    void concurrent_save_throws_optimistic_lock_exception() {
        var id = UUID.randomUUID();
        var entity = new ExecutionEntity(id, taskId.value(), "PLANNING", NOW, null);
        entityManager.persistAndFlush(entity);
        entityManager.detach(entity);

        var fresh = entityManager.find(ExecutionEntity.class, id);
        fresh.setStatus("RUNNING");
        entityManager.flush();

        entity.setStatus("FAILED");
        assertThatThrownBy(() -> {
            entityManager.merge(entity);
            entityManager.flush();
        }).isInstanceOf(OptimisticLockException.class);
    }
}
