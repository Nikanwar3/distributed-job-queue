package com.jobqueue.integration;

import com.jobqueue.domain.Job;
import com.jobqueue.domain.JobStatus;
import com.jobqueue.queue.JobQueuePublisher;
import com.jobqueue.queue.RedisKeys;
import com.jobqueue.repository.JobRepository;
import com.jobqueue.worker.WorkerJobLifecycle;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Simulates a worker process dying mid-delivery by manually taking ownership of a stream message
 * (via a raw {@code XREADGROUP}) and then never acknowledging it — exactly what happens when a
 * worker's JVM is killed after Redis has handed it a message. The worker component itself is
 * disabled in this test context ({@code jobqueue.worker-enabled=false}) so there is no real
 * consumer to race against; {@link com.jobqueue.recovery.AbandonedJobRecoveryScheduler} is the only
 * thing that can move these jobs forward.
 */
class AbandonedJobRecoveryIntegrationTest extends AbstractIntegrationTest {

    @DynamicPropertySource
    static void disableWorker(DynamicPropertyRegistry registry) {
        registry.add("jobqueue.worker-enabled", () -> "false");
    }

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private JobQueuePublisher jobQueuePublisher;

    @Autowired
    private WorkerJobLifecycle lifecycle;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Test
    void jobAbandonedWhileRunningIsRecoveredAndRetried() {
        UUID jobId = UUID.randomUUID();
        Job job = Job.newJob(jobId, null, "slow", "{}", 1, 30, Instant.now());
        jobRepository.saveAndFlush(job);

        // Simulate: a (now-dead) worker claimed the job for execution...
        var claimed = lifecycle.tryStartRunning(jobId, "zombie-worker");
        assertThat(claimed).isPresent();

        // ...and had actually been handed the stream message, which it will never acknowledge.
        deliverAndAbandon(jobId, "zombie-worker");

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Job reloaded = jobRepository.findById(jobId).orElseThrow();
            assertThat(reloaded.getStatus()).isEqualTo(JobStatus.QUEUED);
            assertThat(reloaded.getRetryCount()).isEqualTo(1);
            assertThat(reloaded.getErrorMessage()).contains("crashed");
        });
    }

    @Test
    void jobAbandonedBeforeItWasEverClaimedIsRepublished() {
        UUID jobId = UUID.randomUUID();
        Job job = Job.newJob(jobId, null, "echo", "{}", 3, 30, Instant.now());
        jobRepository.saveAndFlush(job);

        // Delivered to a worker that died before it even called tryStartRunning: job is still QUEUED.
        deliverAndAbandon(jobId, "zombie-worker-2");

        // The recovery scheduler should republish a fresh stream entry for it.
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(readOneRecordFor(jobId)).isTrue());
    }

    /** Reads (and acks, to not interfere with other assertions) a message as a fresh consumer group member. */
    private void deliverAndAbandon(UUID jobId, String consumerName) {
        jobQueuePublisher.publish(jobId);
        redisTemplate.opsForStream().read(
                Consumer.from(RedisKeys.CONSUMER_GROUP, consumerName),
                StreamReadOptions.empty().count(10).block(Duration.ofSeconds(2)),
                StreamOffset.create(RedisKeys.JOB_STREAM, ReadOffset.lastConsumed()));
        // Deliberately not acknowledged: this message now sits in the PEL until recovery claims it.
    }

    private boolean readOneRecordFor(UUID jobId) {
        List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream().read(
                Consumer.from(RedisKeys.CONSUMER_GROUP, "verifier-" + UUID.randomUUID()),
                StreamReadOptions.empty().count(20).block(Duration.ofMillis(500)),
                StreamOffset.create(RedisKeys.JOB_STREAM, ReadOffset.lastConsumed()));
        if (records == null) {
            return false;
        }
        return records.stream().anyMatch(r -> jobId.toString().equals(String.valueOf(r.getValue().get("jobId"))));
    }
}
