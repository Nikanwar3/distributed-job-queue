package com.jobqueue.worker;

import com.jobqueue.config.JobQueueProperties;
import com.jobqueue.domain.Job;
import com.jobqueue.metrics.JobMetrics;
import com.jobqueue.queue.RetryQueue;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Consumes job-ready notifications from the Redis stream and executes them with bounded
 * concurrency. One dedicated thread blocks on {@code XREADGROUP}; actual handler execution runs on
 * a fixed-size pool sized by {@code jobqueue.worker-concurrency}, which is the hard cap on how many
 * jobs this instance processes at once.
 */
@Component
@ConditionalOnProperty(prefix = "jobqueue", name = "worker-enabled", havingValue = "true", matchIfMissing = true)
public class JobWorker {

    private static final Logger log = LoggerFactory.getLogger(JobWorker.class);

    private final StringRedisTemplate redisTemplate;
    private final WorkerIdentity workerIdentity;
    private final JobQueueProperties properties;
    private final WorkerJobLifecycle lifecycle;
    private final JobHandlerRegistry handlerRegistry;
    private final RetryQueue retryQueue;
    private final JobMetrics metrics;
    private final RunningJobRegistry runningJobRegistry;

    private final ConcurrentHashMap<UUID, Boolean> timedOutJobs = new ConcurrentHashMap<>();

    private ExecutorService jobExecutor;
    private ScheduledExecutorService timeoutWatchdog;
    private Thread pollerThread;
    private volatile boolean running = true;

    public JobWorker(StringRedisTemplate redisTemplate,
                      WorkerIdentity workerIdentity,
                      JobQueueProperties properties,
                      WorkerJobLifecycle lifecycle,
                      JobHandlerRegistry handlerRegistry,
                      RetryQueue retryQueue,
                      JobMetrics metrics,
                      RunningJobRegistry runningJobRegistry) {
        this.redisTemplate = redisTemplate;
        this.workerIdentity = workerIdentity;
        this.properties = properties;
        this.lifecycle = lifecycle;
        this.handlerRegistry = handlerRegistry;
        this.retryQueue = retryQueue;
        this.metrics = metrics;
        this.runningJobRegistry = runningJobRegistry;
    }

    @PostConstruct
    void start() {
        this.jobExecutor = Executors.newFixedThreadPool(properties.getWorkerConcurrency(),
                runnable -> new Thread(runnable, "job-exec-" + workerIdentity.id()));
        this.timeoutWatchdog = Executors.newSingleThreadScheduledExecutor(
                runnable -> new Thread(runnable, "job-timeout-watchdog-" + workerIdentity.id()));
        this.pollerThread = new Thread(this::pollLoop, "job-poller-" + workerIdentity.id());
        this.pollerThread.setDaemon(true);
        this.pollerThread.start();
        log.info("Worker {} started with concurrency={}", workerIdentity.id(), properties.getWorkerConcurrency());
    }

    @PreDestroy
    void stop() {
        running = false;
        pollerThread.interrupt();
        jobExecutor.shutdown();
        timeoutWatchdog.shutdown();
        try {
            jobExecutor.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void pollLoop() {
        Consumer consumer = Consumer.from(properties.getConsumerGroup(), workerIdentity.id());
        StreamReadOptions readOptions = StreamReadOptions.empty()
                .count(properties.getConsumerBatchSize())
                .block(Duration.ofMillis(properties.getConsumerBlockMs()));
        String streamKey = properties.getStreamKey();

        while (running) {
            try {
                List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream()
                        .read(consumer, readOptions, StreamOffset.create(streamKey, ReadOffset.lastConsumed()));
                if (records == null || records.isEmpty()) {
                    continue;
                }
                for (MapRecord<String, Object, Object> record : records) {
                    jobExecutor.submit(() -> processRecord(record));
                }
            } catch (Exception e) {
                if (running) {
                    log.error("Error polling job stream, backing off", e);
                    sleepQuietly(Duration.ofSeconds(1));
                }
            }
        }
    }

    private void processRecord(MapRecord<String, Object, Object> record) {
        UUID jobId = UUID.fromString(String.valueOf(record.getValue().get("jobId")));
        MDC.put("jobId", jobId.toString());
        MDC.put("workerId", workerIdentity.id());
        try {
            executeClaimedJob(jobId);
        } finally {
            redisTemplate.opsForStream().acknowledge(properties.getStreamKey(), properties.getConsumerGroup(), record.getId());
            MDC.clear();
        }
    }

    void executeClaimedJob(UUID jobId) {
        var claimedOpt = lifecycle.tryStartRunning(jobId, workerIdentity.id());
        if (claimedOpt.isEmpty()) {
            return;
        }
        WorkerJobLifecycle.ClaimedJob claimed = claimedOpt.get();
        Job job = claimed.job();

        Instant waitStart = job.getNextRetryAt() != null ? job.getNextRetryAt() : job.getCreatedAt();
        metrics.recordQueueWait(Duration.between(waitStart, job.getStartedAt()));

        runningJobRegistry.register(jobId, Thread.currentThread());
        Thread.interrupted(); // clear any stale interrupt flag left on this pooled thread
        ScheduledFuture<?> timeoutFuture = timeoutWatchdog.schedule(
                () -> interruptForTimeout(jobId), job.getTimeoutSeconds(), TimeUnit.SECONDS);

        Instant executionStart = Instant.now();
        try {
            JobHandler handler = handlerRegistry.find(job.getType()).orElse(null);
            if (handler == null) {
                lifecycle.scheduleRetryOrFail(jobId, claimed.executionId(),
                        "no handler registered for job type '" + job.getType() + "'", false, true);
                log.warn("No handler registered for job type '{}', failing job {} permanently", job.getType(), jobId);
                return;
            }

            String result = handler.handle(job.getPayload());
            lifecycle.completeSuccessfully(jobId, claimed.executionId(), result);
            log.info("Job {} completed successfully (attempt {})", jobId, claimed.attemptNumber());
        } catch (Exception ex) {
            boolean timedOut = timedOutJobs.remove(jobId) != null;
            Thread.interrupted(); // clear interrupt status before this thread is reused by the pool
            String message = timedOut
                    ? "execution exceeded timeout of " + job.getTimeoutSeconds() + "s"
                    : describeFailure(ex);
            var decision = lifecycle.scheduleRetryOrFail(jobId, claimed.executionId(), message, timedOut, false);
            if (decision.outcome() == WorkerJobLifecycle.RetryDecision.Outcome.RETRY_SCHEDULED) {
                retryQueue.scheduleRetry(jobId, decision.nextRetryAt());
            }
            log.warn("Job {} attempt {} failed ({}): {}", jobId, claimed.attemptNumber(),
                    timedOut ? "timeout" : "error", message);
        } finally {
            timeoutFuture.cancel(false);
            runningJobRegistry.unregister(jobId);
            metrics.recordExecutionDuration(Duration.between(executionStart, Instant.now()));
        }
    }

    private void interruptForTimeout(UUID jobId) {
        timedOutJobs.put(jobId, Boolean.TRUE);
        runningJobRegistry.interrupt(jobId);
    }

    private static String describeFailure(Exception ex) {
        String message = ex.getMessage();
        return message != null ? message : ex.getClass().getSimpleName();
    }

    private static void sleepQuietly(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Package-private accessor exposed for the recovery scheduler to reuse this worker's pool identity in logs. */
    Set<UUID> timedOutJobsSnapshot() {
        return Set.copyOf(timedOutJobs.keySet());
    }
}
