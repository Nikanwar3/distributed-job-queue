package com.jobqueue.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobqueue.domain.Job;
import com.jobqueue.domain.JobStatus;
import com.jobqueue.service.JobService;
import com.jobqueue.web.dto.CancellationResponse;
import com.jobqueue.web.dto.ExecutionHistoryEntry;
import com.jobqueue.web.dto.JobResponse;
import com.jobqueue.web.dto.JobResultResponse;
import com.jobqueue.web.dto.SubmitJobRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/jobs")
@Tag(name = "Jobs", description = "Submit and manage background jobs")
public class JobController {

    private final JobService jobService;
    private final ObjectMapper objectMapper;

    public JobController(JobService jobService, ObjectMapper objectMapper) {
        this.jobService = jobService;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    @Operation(summary = "Submit a new job (idempotent if idempotencyKey is supplied)")
    public ResponseEntity<JobResponse> submit(@Valid @RequestBody SubmitJobRequest request,
                                               UriComponentsBuilder uriBuilder) throws Exception {
        String payloadJson = objectMapper.writeValueAsString(request.payload());
        JobService.SubmissionResult result = jobService.submit(
                request.idempotencyKey(), request.type(), payloadJson, request.maxRetries(), request.timeoutSeconds());

        URI location = uriBuilder.path("/api/v1/jobs/{id}").buildAndExpand(result.job().getId()).toUri();
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).location(location).body(JobResponse.from(result.job()));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a job's current status and metadata")
    public JobResponse getJob(@PathVariable UUID id) {
        return JobResponse.from(jobService.getJob(id));
    }

    @GetMapping("/{id}/result")
    @Operation(summary = "Get a job's result (null until it completes)")
    public JobResultResponse getResult(@PathVariable UUID id) {
        return JobResultResponse.from(jobService.getJob(id));
    }

    @GetMapping("/{id}/executions")
    @Operation(summary = "Get the full execution attempt history for a job")
    public List<ExecutionHistoryEntry> getExecutionHistory(@PathVariable UUID id) {
        return jobService.getExecutionHistory(id).stream().map(ExecutionHistoryEntry::from).toList();
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel a job if it is queued or running")
    public CancellationResponse cancel(@PathVariable UUID id) {
        return CancellationResponse.of(id, jobService.cancel(id));
    }

    @GetMapping
    @Operation(summary = "List jobs, optionally filtered by status")
    public Page<JobResponse> listJobs(@RequestParam(required = false) JobStatus status, Pageable pageable) {
        Page<Job> jobs = jobService.listJobs(status, pageable);
        return jobs.map(JobResponse::from);
    }
}
