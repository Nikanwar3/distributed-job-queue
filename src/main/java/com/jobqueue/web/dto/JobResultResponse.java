package com.jobqueue.web.dto;

import com.jobqueue.domain.Job;
import com.jobqueue.domain.JobStatus;

import java.util.UUID;

public record JobResultResponse(UUID id, JobStatus status, String result, String errorMessage) {

    public static JobResultResponse from(Job job) {
        return new JobResultResponse(job.getId(), job.getStatus(), job.getResult(), job.getErrorMessage());
    }
}
