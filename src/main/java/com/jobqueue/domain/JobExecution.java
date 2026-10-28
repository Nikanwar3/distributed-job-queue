package com.jobqueue.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * An immutable record of a single execution attempt of a {@link Job}. A job that retries three
 * times before succeeding will have four rows here, giving a full audit trail independent of the
 * job's current (mutable) state.
 */
@Entity
@Table(name = "job_executions")
public class JobExecution {

    @Id
    private UUID id;

    @Column(name = "job_id", nullable = false)
    private UUID jobId;

    @Column(name = "attempt_number", nullable = false)
    private int attemptNumber;

    @Column(name = "worker_id", nullable = false)
    private String workerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ExecutionStatus status;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "error_message")
    private String errorMessage;

    protected JobExecution() {
        // JPA
    }

    public static JobExecution started(UUID jobId, int attemptNumber, String workerId, Instant now) {
        JobExecution execution = new JobExecution();
        execution.id = UUID.randomUUID();
        execution.jobId = jobId;
        execution.attemptNumber = attemptNumber;
        execution.workerId = workerId;
        execution.status = ExecutionStatus.STARTED;
        execution.startedAt = now;
        return execution;
    }

    public void finish(ExecutionStatus status, String errorMessage, Instant now) {
        this.status = status;
        this.errorMessage = errorMessage;
        this.finishedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getJobId() {
        return jobId;
    }

    public int getAttemptNumber() {
        return attemptNumber;
    }

    public String getWorkerId() {
        return workerId;
    }

    public ExecutionStatus getStatus() {
        return status;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public String getErrorMessage() {
        return errorMessage;
    }
}
