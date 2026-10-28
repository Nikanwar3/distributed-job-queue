package com.jobqueue.worker;

import com.jobqueue.domain.ExecutionStatus;
import com.jobqueue.domain.Job;
import com.jobqueue.domain.JobExecution;
import com.jobqueue.domain.JobStatus;
import com.jobqueue.metrics.JobMetrics;
import com.jobqueue.repository.JobExecutionRepository;
import com.jobqueue.repository.JobRepository;
import com.jobqueue.retry.BackoffCalculator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Owns every state transition a worker (as opposed to the public API) is allowed to make.
 *
 * <p>{@link #tryStartRunning} is the linchpin of duplicate-delivery safety: it only succeeds if
 * the job is currently {@code QUEUED}, and relies on JPA optimistic locking ({@code @Version}) to
 * guarantee that if two consumers race on the same job id (e.g. a normal delivery racing a
 * recovery-scheduler redelivery of a reclaimed-but-already-running message), exactly one of them
 * wins the transition and the other backs off without executing the handler twice.
 */
@Service
public class WorkerJobLifecycle {

    private static final Logger log = LoggerFactory.getLogger(WorkerJobLifecycle.class);

    private final JobRepository jobRepository;
    private final JobExecutionRepository jobExecutionRepository;
    private final BackoffCalculator backoffCalculator;
    private final JobMetrics metrics;
    private final TransactionTemplate requiresNewTransaction;

    public WorkerJobLifecycle(JobRepository jobRepository,
                               JobExecutionRepository jobExecutionRepository,
                               BackoffCalculator backoffCalculator,
                               JobMetrics metrics,
                               PlatformTransactionManager transactionManager) {
        this.jobRepository = jobRepository;
        this.jobExecutionRepository = jobExecutionRepository;
        this.backoffCalculator = backoffCalculator;
        this.metrics = metrics;
        this.requiresNewTransaction = new TransactionTemplate(transactionManager);
        this.requiresNewTransaction.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Attempts to claim {@code jobId} for execution on this worker. Returns empty if the job is
     * missing, already terminal (a duplicate delivery of a finished job), already running
     * (another consumer beat us to it), or was concurrently modified (optimistic lock loss).
     *
     * <p>The claim attempt runs in its own {@code REQUIRES_NEW} transaction rather than relying on
     * this method's own {@code @Transactional} boundary. A failed optimistic-lock check aborts the
     * underlying database transaction such that no further statement can run on it until it's
     * rolled back (Hibernate marks the persistence context rollback-only); catching the exception
     * and continuing within that same transaction doesn't work; the commit Spring attempts when this
     * method returns normally fails with {@code UnexpectedRollbackException} instead. Isolating the
     * attempt in its own transaction means a lost race rolls back cleanly and in full before this
     * method returns, every time.
     */
    public Optional<ClaimedJob> tryStartRunning(UUID jobId, String workerId) {
        ClaimedJob claimed;
        try {
            claimed = requiresNewTransaction.execute(status -> {
                Job job = jobRepository.findById(jobId).orElse(null);
                if (job == null) {
                    log.warn("Job {} referenced by stream message no longer exists", jobId);
                    return null;
                }
                if (job.getStatus() != JobStatus.QUEUED) {
                    log.debug("Skipping job {} in status {} (duplicate or stale delivery)", jobId, job.getStatus());
                    metrics.duplicateDeliverySkipped();
                    return null;
                }

                Instant now = Instant.now();
                job.setStatus(JobStatus.RUNNING);
                job.setStartedAt(now);
                job.setUpdatedAt(now);
                jobRepository.saveAndFlush(job);

                int attemptNumber = job.getRetryCount() + 1;
                JobExecution execution = JobExecution.started(jobId, attemptNumber, workerId, now);
                jobExecutionRepository.save(execution);

                return new ClaimedJob(job, execution.getId(), attemptNumber);
            });
        } catch (OptimisticLockingFailureException e) {
            log.debug("Lost race to claim job {}", jobId);
            return Optional.empty();
        }

        return Optional.ofNullable(claimed);
    }

    /**
     * Fresh read of whether cancellation has been requested for a running job — used by the worker
     * to catch a cancellation broadcast that raced ahead of it registering for interruption. See
     * {@link JobWorker#executeClaimedJob}.
     */
    @Transactional(readOnly = true)
    public boolean isCancelRequested(UUID jobId) {
        return jobRepository.findById(jobId).map(Job::isCancelRequested).orElse(false);
    }

    @Transactional
    public void completeSuccessfully(UUID jobId, UUID executionId, String result) {
        Instant now = Instant.now();
        Job job = requireJob(jobId);
        boolean cancelled = job.isCancelRequested();
        job.setStatus(cancelled ? JobStatus.CANCELLED : JobStatus.COMPLETED);
        job.setResult(result);
        job.setCompletedAt(now);
        job.setUpdatedAt(now);
        jobRepository.save(job);
        finishExecution(executionId, cancelled ? ExecutionStatus.FAILED : ExecutionStatus.SUCCEEDED,
                cancelled ? "cancelled after completion raced with cancellation" : null, now);
        if (cancelled) {
            metrics.jobCancelled();
        } else {
            metrics.jobCompleted();
        }
    }

    /**
     * Records a failed attempt and either schedules a retry (returned in the decision so the
     * caller can enqueue it in the delayed-retry set) or marks the job permanently failed.
     *
     * @param permanent when true, no retry is attempted regardless of remaining budget (used e.g.
     *                  when no handler is registered for the job's type)
     */
    @Transactional
    public RetryDecision scheduleRetryOrFail(UUID jobId, UUID executionId, String errorMessage,
                                              boolean timedOut, boolean permanent) {
        Instant now = Instant.now();
        Job job = requireJob(jobId);
        finishExecution(executionId, timedOut ? ExecutionStatus.TIMED_OUT : ExecutionStatus.FAILED, errorMessage, now);

        if (job.isCancelRequested()) {
            job.setStatus(JobStatus.CANCELLED);
            job.setCompletedAt(now);
            job.setUpdatedAt(now);
            jobRepository.save(job);
            metrics.jobCancelled();
            return RetryDecision.cancelled();
        }

        job.incrementRetryCount();
        job.setErrorMessage(errorMessage);
        job.setUpdatedAt(now);

        if (!permanent && job.hasRetriesRemaining()) {
            Duration delay = backoffCalculator.delayForAttempt(job.getRetryCount());
            Instant nextRetryAt = now.plus(delay);
            job.setStatus(JobStatus.QUEUED);
            job.setNextRetryAt(nextRetryAt);
            jobRepository.save(job);
            metrics.jobRetried();
            return RetryDecision.retryAt(nextRetryAt);
        }

        job.setStatus(JobStatus.FAILED);
        job.setCompletedAt(now);
        jobRepository.save(job);
        metrics.jobFailed();
        return RetryDecision.failedPermanently();
    }

    private void finishExecution(UUID executionId, ExecutionStatus status, String errorMessage, Instant now) {
        jobExecutionRepository.findById(executionId).ifPresent(execution -> {
            execution.finish(status, errorMessage, now);
            jobExecutionRepository.save(execution);
        });
    }

    private Job requireJob(UUID jobId) {
        return jobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalStateException("Job " + jobId + " vanished mid-execution"));
    }

    public record ClaimedJob(Job job, UUID executionId, int attemptNumber) {
    }

    public record RetryDecision(Outcome outcome, Instant nextRetryAt) {
        public enum Outcome { RETRY_SCHEDULED, FAILED_PERMANENTLY, CANCELLED }

        public static RetryDecision retryAt(Instant nextRetryAt) {
            return new RetryDecision(Outcome.RETRY_SCHEDULED, nextRetryAt);
        }

        public static RetryDecision failedPermanently() {
            return new RetryDecision(Outcome.FAILED_PERMANENTLY, null);
        }

        public static RetryDecision cancelled() {
            return new RetryDecision(Outcome.CANCELLED, null);
        }
    }
}
