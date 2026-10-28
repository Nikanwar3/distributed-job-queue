package com.jobqueue.recovery;

import com.jobqueue.queue.JobQueuePublisher;
import com.jobqueue.queue.RetryQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Promotes due retries from the delayed-visibility sorted set back onto the live stream.
 * {@link RetryQueue#claimDueRetries} removes entries atomically, so if several instances run this
 * scheduler concurrently, each due job is republished exactly once.
 */
@Component
public class DelayedRetryScheduler {

    private static final Logger log = LoggerFactory.getLogger(DelayedRetryScheduler.class);
    private static final int BATCH_SIZE = 100;

    private final RetryQueue retryQueue;
    private final JobQueuePublisher jobQueuePublisher;

    public DelayedRetryScheduler(RetryQueue retryQueue, JobQueuePublisher jobQueuePublisher) {
        this.retryQueue = retryQueue;
        this.jobQueuePublisher = jobQueuePublisher;
    }

    @Scheduled(fixedDelayString = "${jobqueue.delayed-retry-interval-ms:1000}")
    public void promoteDueRetries() {
        Set<UUID> due = retryQueue.claimDueRetries(Instant.now(), BATCH_SIZE);
        for (UUID jobId : due) {
            jobQueuePublisher.publish(jobId);
            log.debug("Promoted job {} from delayed retry set back onto the stream", jobId);
        }
    }
}
