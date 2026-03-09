package com.echoflow.infrastructure.persistence.execution;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "execution_step")
public class ExecutionStepEntity {

    @Id
    private UUID id;

    @Column(name = "execution_id", nullable = false)
    private UUID executionId;

    @Column(name = "step_order", nullable = false)
    private int stepOrder;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 20)
    private String type;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(columnDefinition = "TEXT")
    private String output;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "step", fetch = FetchType.EAGER, cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("loggedAt ASC")
    private List<StepLogEntity> logs = new ArrayList<>();

    protected ExecutionStepEntity() {}

    public ExecutionStepEntity(UUID id, UUID executionId, int stepOrder, String name,
                               String type, String status, String output, Instant createdAt) {
        this.id = id;
        this.executionId = executionId;
        this.stepOrder = stepOrder;
        this.name = name;
        this.type = type;
        this.status = status;
        this.output = output;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getExecutionId() { return executionId; }
    public int getStepOrder() { return stepOrder; }
    public String getName() { return name; }
    public String getType() { return type; }
    public String getStatus() { return status; }
    public String getOutput() { return output; }
    public Instant getCreatedAt() { return createdAt; }
    public List<StepLogEntity> getLogs() { return logs; }

    public void setStatus(String status) { this.status = status; }
    public void setOutput(String output) { this.output = output; }
    public void setLogs(List<StepLogEntity> logs) { this.logs = logs; }
}
