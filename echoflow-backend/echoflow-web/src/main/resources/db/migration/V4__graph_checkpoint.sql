-- V4: Graph checkpoint persistence for StateGraph audit trail.
-- Stores StateGraph checkpoints per execution thread.

CREATE TABLE graph_checkpoint (
    id             UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    thread_id      VARCHAR(100) NOT NULL,
    node_id        VARCHAR(100),
    next_node_id   VARCHAR(100),
    state          JSONB        NOT NULL DEFAULT '{}',
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_graph_checkpoint_thread_id ON graph_checkpoint(thread_id);
