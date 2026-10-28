package com.jobqueue.recovery;

import com.jobqueue.config.JobQueueProperties;
import com.jobqueue.domain.Job;
import com.jobqueue.domain.JobExecution;
import com.jobqueue.domain.JobStatus;
import com.jobqueue.metrics.JobMetrics;
import com.jobqueue.queue.JobQueuePublisher;
import com.jobqueue.queue.RetryQueue;
import com.jobqueue.repository.JobExecutionRepository;
import com.jobqueue.repository.JobRepository;
import com.jobqueue.worker.WorkerIdentity;
import com.jobqueue.worker.WorkerJobLifecycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Reclaims stream messages that were delivered to a consumer which then crashed (or stalled)
 * before acknowledging them, so the underlying job isn't lost forever in the group's
 * pending-entries list.
 *
 * <p>A message only becomes eligible once it has been idle (undelivered-and-unacked) for longer
 * than {@code jobqueue.recovery-min-idle-ms} — comfortably longer than any job's own timeout — so
 * that a job still being legitimately processed is never double-run by this scheduler.
 */
@Component
public class AbandonedJobRecoveryScheduler {

    private static final Logger log = LoggerFactory.getLogger(AbandonedJobRecoveryScheduler.class);
    private static final int SCAN_BATCH_SIZE = 100;

    private final StringRedisTemplate redisTemplate;
    private final JobRepository jobRepository;
    private final JobExecutionRepository jobExecutionRepository;
    private final WorkerJobLifecycle lifecycle;
    private final JobQueuePublisher jobQueuePublisher;
    private final RetryQueue retryQueue;
    private final WorkerIdentity workerIdentity;
    private final JobMetrics metrics;
    private final JobQueueProperties properties;

    public AbandonedJobRecoveryScheduler(StringRedisTemplate redisTemplate,
                                          JobRepository jobRepository,
                                          JobExecutionRepository jobExecutionRepository,
                                          WorkerJobLifecycle lifecycle,
                                          JobQueuePublisher jobQueuePublisher,
                                          RetryQueue retryQueue,
                                          WorkerIdentity workerIdentity,
                                          JobMetrics metrics,
                                          JobQueueProperties properties) {
        this.redisTemplate = redisTemplate;
        this.jobRepository = jobRepository;
        this.jobExecutionRepository = jobExecutionRepository;
        this.lifecycle = lifecycle;
        this.jobQueuePublisher = jobQueuePublisher;
        this.retryQueue = retryQueue;
        this.workerIdentity = workerIdentity;
        this.metrics = metrics;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${jobqueue.recovery-interval-ms:15000}")
    public void recoverAbandonedMessages() {
        PendingMessages pending;
        try {
            pending = redisTemplate.opsForStream()
                    .pending(properties.getStreamKey(), properties.getConsumerGroup(), Range.unbounded(), SCAN_BATCH_SIZE);
        } catch (Exception e) {
            log.error("Failed to scan pending entries for recovery", e);
            return;
        }

        Duration minIdle = Duration.ofMillis(minIdleMs());
        for (PendingMessage message : pending) {
            if (message.getElapsedTimeSinceLastDelivery().compareTo(minIdle) < 0) {
                continue;
            }
            reclaim(message.getIdAsString(), minIdle);
        }
    }

    private void reclaim(String recordId, Duration minIdle) {
        String recoveryConsumer = workerIdentity.id() + "-recovery";
        String streamKey = properties.getStreamKey();
        String consumerGroup = properties.getConsumerGroup();
        List<org.springframework.data.redis.connection.stream.MapRecord<String, Object, Object>> claimed;
        try {
            claimed = redisTemplate.opsForStream().claim(
                    streamKey, consumerGroup, recoveryConsumer, minIdle, RecordId.of(recordId));
        } catch (Exception e) {
            // Another instance's recovery scheduler may have claimed it first between our scan and
            // this call; that's expected under concurrent recovery and not an error.
            log.debug("Could not claim pending record {}: {}", recordId, e.getMessage());
            return;
        }
        if (claimed.isEmpty()) {
            return;
        }

        UUID jobId = UUID.fromString(String.valueOf(claimed.get(0).getValue().get("jobId")));
        try {
            recoverJob(jobId);
        } finally {
            redisTemplate.opsForStream().acknowledge(streamKey, consumerGroup, recordId);
        }
    }

    private void recoverJob(UUID jobId) {
        Job job = jobRepository.findById(jobId).orElse(null);
        if (job == null || job.getStatus().isTerminal()) {
            log.debug("Recovered message for job {} needs no action (status={})", jobId,
                    job == null ? "deleted" : job.getStatus());
            return;
        }

        if (job.getStatus() == JobStatus.QUEUED) {
            // Delivered once, but the consumer died before it could even claim the job for
            // execution. Simply hand it a fresh stream entry.
            jobQueuePublisher.publish(jobId);
            metrics.jobRecovered();
            log.info("Recovered never-started job {} by republishing", jobId);
            return;
        }

        // status == RUNNING: a worker claimed it and then crashed before finishing.
        Optional<JobExecution> dangling = jobExecutionRepository
                .findFirstByJobIdAndFinishedAtIsNullOrderByAttemptNumberDesc(jobId);
        UUID executionId = dangling.map(JobExecution::getId).orElse(null);
        if (executionId == null) {
            log.warn("Job {} was RUNNING but has no open execution record; marking failed directly", jobId);
            return;
        }

        var decision = lifecycle.scheduleRetryOrFail(jobId, executionId,
                "worker crashed before completing this job (recovered after idle timeout)", false, false);
        if (decision.outcome() == WorkerJobLifecycle.RetryDecision.Outcome.RETRY_SCHEDULED) {
            retryQueue.scheduleRetry(jobId, decision.nextRetryAt());
        }
        metrics.jobRecovered();
        log.info("Recovered abandoned job {} (outcome={})", jobId, decision.outcome());
    }

    private long minIdleMs() {
        return properties.getRecoveryMinIdleMs();
    }
}
