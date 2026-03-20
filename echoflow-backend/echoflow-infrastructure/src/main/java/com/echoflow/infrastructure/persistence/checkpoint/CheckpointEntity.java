package com.echoflow.infrastructure.persistence.checkpoint;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "graph_checkpoint")
public class CheckpointEntity {

    @Id
    private UUID id;

    @Column(name = "thread_id", nullable = false, length = 100)
    private String threadId;

    @Column(name = "node_id", length = 100)
    private String nodeId;

    @Column(name = "next_node_id", length = 100)
    private String nextNodeId;

    @Column(nullable = false, columnDefinition = "JSONB")
    private String state;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected CheckpointEntity() {}

    public CheckpointEntity(UUID id, String threadId, String nodeId,
                            String nextNodeId, String state, Instant createdAt) {
        this.id = id;
        this.threadId = threadId;
        this.nodeId = nodeId;
        this.nextNodeId = nextNodeId;
        this.state = state;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public String getThreadId() { return threadId; }
    public String getNodeId() { return nodeId; }
    public String getNextNodeId() { return nextNodeId; }
    public String getState() { return state; }
    public Instant getCreatedAt() { return createdAt; }

    public void setNodeId(String nodeId) { this.nodeId = nodeId; }
    public void setNextNodeId(String nextNodeId) { this.nextNodeId = nextNodeId; }
    public void setState(String state) { this.state = state; }
}
