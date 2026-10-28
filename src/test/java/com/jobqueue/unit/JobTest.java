package com.jobqueue.unit;

import com.jobqueue.domain.Job;
import com.jobqueue.domain.JobStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JobTest {

    @Test
    void newJobStartsQueuedWithZeroRetries() {
        Job job = Job.newJob(UUID.randomUUID(), null, "echo", "{}", 3, 30, Instant.now());

        assertThat(job.getStatus()).isEqualTo(JobStatus.QUEUED);
        assertThat(job.getRetryCount()).isZero();
        assertThat(job.hasRetriesRemaining()).isTrue();
    }

    @Test
    void retriesAreExhaustedOnceCountReachesMax() {
        Job job = Job.newJob(UUID.randomUUID(), null, "echo", "{}", 2, 30, Instant.now());

        job.incrementRetryCount();
        assertThat(job.hasRetriesRemaining()).isTrue();

        job.incrementRetryCount();
        assertThat(job.hasRetriesRemaining()).isFalse();
    }
}
