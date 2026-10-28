package com.jobqueue.web.dto;

import com.jobqueue.domain.ExecutionStatus;
import com.jobqueue.domain.JobExecution;

import java.time.Instant;

public record ExecutionHistoryEntry(
        int attemptNumber,
        String workerId,
        ExecutionStatus status,
        Instant startedAt,
        Instant finishedAt,
        String errorMessage
) {
    public static ExecutionHistoryEntry from(JobExecution execution) {
        return new ExecutionHistoryEntry(
                execution.getAttemptNumber(),
                execution.getWorkerId(),
                execution.getStatus(),
                execution.getStartedAt(),
                execution.getFinishedAt(),
                execution.getErrorMessage());
    }
}
