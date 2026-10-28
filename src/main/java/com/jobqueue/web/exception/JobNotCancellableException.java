package com.jobqueue.web.exception;

import com.jobqueue.domain.JobStatus;

import java.util.UUID;

public class JobNotCancellableException extends RuntimeException {

    public JobNotCancellableException(UUID jobId, JobStatus currentStatus) {
        super("Job " + jobId + " cannot be cancelled from status " + currentStatus);
    }
}
