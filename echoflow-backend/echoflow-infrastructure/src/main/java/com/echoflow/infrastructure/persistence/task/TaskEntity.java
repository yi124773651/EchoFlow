package com.echoflow.infrastructure.persistence.task;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "task")
public class TaskEntity {

    @Id
    private UUID id;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "webhook_url", length = 500)
    private String webhookUrl;

    @Version
    private Long version;

    protected TaskEntity() {}

    public TaskEntity(UUID id, String description, String status, Instant createdAt, Instant completedAt, String webhookUrl) {
        this.id = id;
        this.description = description;
        this.status = status;
        this.createdAt = createdAt;
        this.completedAt = completedAt;
        this.webhookUrl = webhookUrl;
    }

    public UUID getId() { return id; }
    public String getDescription() { return description; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getCompletedAt() { return completedAt; }
    public String getWebhookUrl() { return webhookUrl; }

    public void setStatus(String status) { this.status = status; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
}
