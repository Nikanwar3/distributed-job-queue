package com.jobqueue.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * A unit of work submitted to the platform. This row is the single source of truth for a job's
 * lifecycle state; the Redis stream only carries lightweight delivery pointers (the job id).
 */
@Entity
@Table(name = "jobs")
public class Job {

    @Id
    private UUID id;

    /** Optional client-supplied key used to deduplicate resubmission of the same logical job. */
    @Column(name = "idempotency_key", unique = true)
    private String idempotencyKey;

    @Column(nullable = false)
    private String type;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private JobStatus status;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "max_retries", nullable = false)
    private int maxRetries;

    @Column(name = "timeout_seconds", nullable = false)
    private int timeoutSeconds;

    @Column(name = "next_retry_at")
    private Instant nextRetryAt;

    @Column(name = "cancel_requested", nullable = false)
    private boolean cancelRequested;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String result;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Version
    private long version;

    protected Job() {
        // JPA
    }

    public static Job newJob(UUID id, String idempotencyKey, String type, String payload,
                              int maxRetries, int timeoutSeconds, Instant now) {
        Job job = new Job();
        job.id = id;
        job.idempotencyKey = idempotencyKey;
        job.type = type;
        job.payload = payload;
        job.status = JobStatus.QUEUED;
        job.retryCount = 0;
        job.maxRetries = maxRetries;
        job.timeoutSeconds = timeoutSeconds;
        job.cancelRequested = false;
        job.createdAt = now;
        job.updatedAt = now;
        return job;
    }

    public UUID getId() {
        return id;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getType() {
        return type;
    }

    public String getPayload() {
        return payload;
    }

    public JobStatus getStatus() {
        return status;
    }

    public void setStatus(JobStatus status) {
        this.status = status;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public void incrementRetryCount() {
        this.retryCount++;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public Instant getNextRetryAt() {
        return nextRetryAt;
    }

    public void setNextRetryAt(Instant nextRetryAt) {
        this.nextRetryAt = nextRetryAt;
    }

    public boolean isCancelRequested() {
        return cancelRequested;
    }

    public void setCancelRequested(boolean cancelRequested) {
        this.cancelRequested = cancelRequested;
    }

    public String getResult() {
        return result;
    }

    public void setResult(String result) {
        this.result = result;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public long getVersion() {
        return version;
    }

    public boolean hasRetriesRemaining() {
        return retryCount < maxRetries;
    }
}
