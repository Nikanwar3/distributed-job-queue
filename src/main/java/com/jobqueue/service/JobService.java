package com.jobqueue.service;

import com.jobqueue.config.JobQueueProperties;
import com.jobqueue.domain.Job;
import com.jobqueue.domain.JobExecution;
import com.jobqueue.domain.JobStatus;
import com.jobqueue.metrics.JobMetrics;
import com.jobqueue.queue.CancellationPublisher;
import com.jobqueue.queue.JobQueuePublisher;
import com.jobqueue.repository.JobExecutionRepository;
import com.jobqueue.repository.JobRepository;
import com.jobqueue.web.exception.JobNotCancellableException;
import com.jobqueue.web.exception.JobNotFoundException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Client-facing job operations: submission (with idempotency), lookup, cancellation, and
 * execution history. Worker-side state transitions live in
 * {@link com.jobqueue.worker.WorkerJobLifecycle} to keep the two mutation paths (API vs. worker)
 * independently reasoned about.
 */
@Service
public class JobService {

    private static final Logger log = LoggerFactory.getLogger(JobService.class);

    private final JobRepository jobRepository;
    private final JobExecutionRepository jobExecutionRepository;
    private final JobQueuePublisher jobQueuePublisher;
    private final CancellationPublisher cancellationPublisher;
    private final JobQueueProperties properties;
    private final JobMetrics metrics;
    private final EntityManager entityManager;

    public JobService(JobRepository jobRepository,
                       JobExecutionRepository jobExecutionRepository,
                       JobQueuePublisher jobQueuePublisher,
                       CancellationPublisher cancellationPublisher,
                       JobQueueProperties properties,
                       JobMetrics metrics,
                       EntityManager entityManager) {
        this.jobRepository = jobRepository;
        this.jobExecutionRepository = jobExecutionRepository;
        this.jobQueuePublisher = jobQueuePublisher;
        this.cancellationPublisher = cancellationPublisher;
        this.properties = properties;
        this.metrics = metrics;
        this.entityManager = entityManager;
    }

    /**
     * Submits a new job. If {@code idempotencyKey} matches an existing job, that job is returned
     * unchanged instead of creating a duplicate — this makes client-side retries of the submit
     * call safe.
     */
    @Transactional
    public SubmissionResult submit(String idempotencyKey, String type, String payload,
                                    Integer maxRetries, Integer timeoutSeconds) {
        if (idempotencyKey != null) {
            Optional<Job> existing = jobRepository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                log.info("Idempotent resubmission matched existing job {} for key {}", existing.get().getId(), idempotencyKey);
                return new SubmissionResult(existing.get(), false);
            }
        }

        UUID id = UUID.randomUUID();
        Job job = Job.newJob(
                id,
                idempotencyKey,
                type,
                payload,
                maxRetries != null ? maxRetries : properties.getDefaultMaxRetries(),
                timeoutSeconds != null ? timeoutSeconds : properties.getDefaultTimeoutSeconds(),
                Instant.now());

        try {
            jobRepository.saveAndFlush(job);
        } catch (DataIntegrityViolationException e) {
            // Two concurrent requests raced on the same idempotency key; the loser falls back to
            // the winner's row instead of failing the client.
            if (idempotencyKey != null) {
                Job winner = jobRepository.findByIdempotencyKey(idempotencyKey)
                        .orElseThrow(() -> e);
                log.info("Idempotency key {} raced; returning winning job {}", idempotencyKey, winner.getId());
                return new SubmissionResult(winner, false);
            }
            throw e;
        }

        jobQueuePublisher.publish(job.getId());
        metrics.jobSubmitted();
        return new SubmissionResult(job, true);
    }

    @Transactional(readOnly = true)
    public Job getJob(UUID jobId) {
        return jobRepository.findById(jobId).orElseThrow(() -> new JobNotFoundException(jobId));
    }

    @Transactional(readOnly = true)
    public List<JobExecution> getExecutionHistory(UUID jobId) {
        if (!jobRepository.existsById(jobId)) {
            throw new JobNotFoundException(jobId);
        }
        return jobExecutionRepository.findByJobIdOrderByAttemptNumberAsc(jobId);
    }

    @Transactional(readOnly = true)
    public Page<Job> listJobs(JobStatus status, Pageable pageable) {
        if (status == null) {
            return jobRepository.findAll(pageable);
        }
        return jobRepository.findAll((root, query, cb) -> cb.equal(root.get("status"), status), pageable);
    }

    /**
     * Cancels a job if it's in a cancellable state. Queued jobs are cancelled immediately and
     * will never run. Running jobs receive a best-effort interrupt broadcast — see README
     * "Cancellation semantics" for the consistency limitations of cancelling a job already in
     * flight on another instance.
     */
    @Transactional
    public CancellationOutcome cancel(UUID jobId) {
        // PESSIMISTIC_WRITE avoids a lost-update race against a worker concurrently transitioning
        // this same row from QUEUED to RUNNING.
        Job job = entityManager.find(Job.class, jobId, LockModeType.PESSIMISTIC_WRITE);
        if (job == null) {
            throw new JobNotFoundException(jobId);
        }

        return switch (job.getStatus()) {
            case QUEUED -> {
                job.setStatus(JobStatus.CANCELLED);
                job.setCompletedAt(Instant.now());
                job.setUpdatedAt(Instant.now());
                metrics.jobCancelled();
                yield CancellationOutcome.CANCELLED_IMMEDIATELY;
            }
            case RUNNING -> {
                job.setCancelRequested(true);
                job.setUpdatedAt(Instant.now());
                cancellationPublisher.requestCancellation(jobId);
                yield CancellationOutcome.CANCELLATION_REQUESTED;
            }
            case COMPLETED, FAILED, CANCELLED ->
                    throw new JobNotCancellableException(jobId, job.getStatus());
        };
    }

    public record SubmissionResult(Job job, boolean created) {
    }
}
