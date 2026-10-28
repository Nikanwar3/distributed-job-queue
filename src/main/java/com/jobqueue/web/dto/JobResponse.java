package com.jobqueue.web.dto;

import com.jobqueue.domain.Job;
import com.jobqueue.domain.JobStatus;

import java.time.Instant;
import java.util.UUID;

public record JobResponse(
        UUID id,
        String idempotencyKey,
        String type,
        JobStatus status,
        int retryCount,
        int maxRetries,
        int timeoutSeconds,
        Instant nextRetryAt,
        Instant createdAt,
        Instant updatedAt,
        Instant startedAt,
        Instant completedAt,
        String errorMessage
) {
    public static JobResponse from(Job job) {
        return new JobResponse(
                job.getId(),
                job.getIdempotencyKey(),
                job.getType(),
                job.getStatus(),
                job.getRetryCount(),
                job.getMaxRetries(),
                job.getTimeoutSeconds(),
                job.getNextRetryAt(),
                job.getCreatedAt(),
                job.getUpdatedAt(),
                job.getStartedAt(),
                job.getCompletedAt(),
                job.getErrorMessage());
    }
}
