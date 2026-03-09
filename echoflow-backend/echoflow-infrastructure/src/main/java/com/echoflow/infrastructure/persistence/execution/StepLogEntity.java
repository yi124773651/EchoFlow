package com.echoflow.infrastructure.persistence.execution;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "step_log")
public class StepLogEntity {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "step_id", nullable = false)
    private ExecutionStepEntity step;

    @Column(nullable = false, length = 20)
    private String type;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "logged_at", nullable = false)
    private Instant loggedAt;

    protected StepLogEntity() {}

    public StepLogEntity(UUID id, ExecutionStepEntity step, String type, String content, Instant loggedAt) {
        this.id = id;
        this.step = step;
        this.type = type;
        this.content = content;
        this.loggedAt = loggedAt;
    }

    public UUID getId() { return id; }
    public ExecutionStepEntity getStep() { return step; }
    public String getType() { return type; }
    public String getContent() { return content; }
    public Instant getLoggedAt() { return loggedAt; }
}
