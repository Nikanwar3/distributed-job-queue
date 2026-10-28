package com.jobqueue.integration;

import com.jobqueue.domain.Job;
import com.jobqueue.domain.JobStatus;
import com.jobqueue.repository.JobExecutionRepository;
import com.jobqueue.repository.JobRepository;
import com.jobqueue.worker.WorkerJobLifecycle;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises {@link WorkerJobLifecycle#tryStartRunning} directly: this is the exact compare-and-swap
 * two competing deliveries of the same job id race against (e.g. a normal consumer delivery racing
 * a recovery-scheduler redelivery of a reclaimed-but-still-running message).
 */
class DuplicateDeliveryIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private JobExecutionRepository jobExecutionRepository;

    @Autowired
    private WorkerJobLifecycle lifecycle;

    @Test
    void onlyOneOfTwoConcurrentDeliveriesClaimsTheJob() throws Exception {
        UUID jobId = UUID.randomUUID();
        Job job = Job.newJob(jobId, null, "echo", "{}", 3, 30, Instant.now());
        jobRepository.saveAndFlush(job);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch startLine = new CountDownLatch(1);

        Future<Boolean> claimA = pool.submit(() -> claim(jobId, "worker-A", startLine));
        Future<Boolean> claimB = pool.submit(() -> claim(jobId, "worker-B", startLine));
        startLine.countDown();

        boolean aClaimed = claimA.get(10, TimeUnit.SECONDS);
        boolean bClaimed = claimB.get(10, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(aClaimed ^ bClaimed).as("exactly one delivery should win the claim").isTrue();

        Job persisted = jobRepository.findById(jobId).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(JobStatus.RUNNING);

        List<?> executions = jobExecutionRepository.findByJobIdOrderByAttemptNumberAsc(jobId);
        assertThat(executions).hasSize(1);
    }

    @Test
    void redeliveryOfAnAlreadyTerminalJobIsSkippedWithoutReexecuting() {
        UUID jobId = UUID.randomUUID();
        Job job = Job.newJob(jobId, null, "echo", "{}", 3, 30, Instant.now());
        job.setStatus(JobStatus.COMPLETED);
        job.setCompletedAt(Instant.now());
        jobRepository.saveAndFlush(job);

        var claimed = lifecycle.tryStartRunning(jobId, "late-redelivery-worker");

        assertThat(claimed).isEmpty();
        assertThat(jobExecutionRepository.findByJobIdOrderByAttemptNumberAsc(jobId)).isEmpty();
    }

    private boolean claim(UUID jobId, String workerId, CountDownLatch startLine) throws InterruptedException {
        startLine.await();
        return lifecycle.tryStartRunning(jobId, workerId).isPresent();
    }
}
