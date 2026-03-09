-- Task and Execution tables for MVP

CREATE TABLE task (
    id           UUID         PRIMARY KEY,
    description  TEXT         NOT NULL,
    status       VARCHAR(20)  NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL,
    completed_at TIMESTAMPTZ
);

CREATE TABLE execution (
    id           UUID         PRIMARY KEY,
    task_id      UUID         NOT NULL REFERENCES task(id),
    status       VARCHAR(20)  NOT NULL,
    started_at   TIMESTAMPTZ  NOT NULL,
    completed_at TIMESTAMPTZ
);

CREATE INDEX idx_execution_task_id ON execution(task_id);

CREATE TABLE execution_step (
    id           UUID         PRIMARY KEY,
    execution_id UUID         NOT NULL REFERENCES execution(id),
    step_order   INT          NOT NULL,
    name         VARCHAR(100) NOT NULL,
    type         VARCHAR(20)  NOT NULL,
    status       VARCHAR(20)  NOT NULL,
    output       TEXT,
    created_at   TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_execution_step_execution_id ON execution_step(execution_id);

CREATE TABLE step_log (
    id        UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    step_id   UUID         NOT NULL REFERENCES execution_step(id),
    type      VARCHAR(20)  NOT NULL,
    content   TEXT         NOT NULL,
    logged_at TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_step_log_step_id ON step_log(step_id);
