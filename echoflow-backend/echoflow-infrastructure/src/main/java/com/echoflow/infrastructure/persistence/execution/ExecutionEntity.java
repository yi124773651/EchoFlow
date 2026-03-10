package com.echoflow.infrastructure.persistence.execution;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "execution")
public class ExecutionEntity {

    @Id
    private UUID id;

    @Column(name = "task_id", nullable = false)
    private UUID taskId;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Version
    private Long version;

    @OneToMany(fetch = FetchType.EAGER, cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "execution_id")
    @OrderBy("stepOrder ASC")
    private List<ExecutionStepEntity> steps = new ArrayList<>();

    protected ExecutionEntity() {}

    public ExecutionEntity(UUID id, UUID taskId, String status, Instant startedAt, Instant completedAt) {
        this.id = id;
        this.taskId = taskId;
        this.status = status;
        this.startedAt = startedAt;
        this.completedAt = completedAt;
    }

    public UUID getId() { return id; }
    public UUID getTaskId() { return taskId; }
    public String getStatus() { return status; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public List<ExecutionStepEntity> getSteps() { return steps; }

    public void setStatus(String status) { this.status = status; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
    public void setSteps(List<ExecutionStepEntity> steps) { this.steps = steps; }
}
