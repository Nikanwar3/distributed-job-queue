CREATE TABLE jobs (
    id               UUID PRIMARY KEY,
    idempotency_key  VARCHAR(255) UNIQUE,
    type             VARCHAR(100)  NOT NULL,
    payload          JSONB         NOT NULL,
    status           VARCHAR(20)   NOT NULL,
    retry_count      INTEGER       NOT NULL DEFAULT 0,
    max_retries      INTEGER       NOT NULL DEFAULT 3,
    timeout_seconds  INTEGER       NOT NULL DEFAULT 30,
    next_retry_at    TIMESTAMPTZ,
    cancel_requested BOOLEAN       NOT NULL DEFAULT FALSE,
    result           JSONB,
    error_message    TEXT,
    created_at       TIMESTAMPTZ   NOT NULL,
    updated_at       TIMESTAMPTZ   NOT NULL,
    started_at       TIMESTAMPTZ,
    completed_at     TIMESTAMPTZ,
    version          BIGINT        NOT NULL DEFAULT 0,

    CONSTRAINT chk_jobs_status CHECK (status IN ('QUEUED', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED'))
);

CREATE INDEX idx_jobs_status ON jobs (status);
CREATE INDEX idx_jobs_created_at ON jobs (created_at);

CREATE TABLE job_executions (
    id             UUID PRIMARY KEY,
    job_id         UUID          NOT NULL REFERENCES jobs (id),
    attempt_number INTEGER       NOT NULL,
    worker_id      VARCHAR(255)  NOT NULL,
    status         VARCHAR(20)   NOT NULL,
    started_at     TIMESTAMPTZ   NOT NULL,
    finished_at    TIMESTAMPTZ,
    error_message  TEXT,

    CONSTRAINT chk_executions_status CHECK (status IN ('STARTED', 'SUCCEEDED', 'FAILED', 'TIMED_OUT')),
    CONSTRAINT uq_job_attempt UNIQUE (job_id, attempt_number)
);

CREATE INDEX idx_job_executions_job_id ON job_executions (job_id);
