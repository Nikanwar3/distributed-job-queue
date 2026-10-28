package com.jobqueue.retry;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Computes exponential backoff delays with jitter.
 *
 * <p>Delay for attempt {@code n} (1-indexed) is {@code baseDelay * 2^(n-1)}, capped at
 * {@code maxDelay}, with up to +/-25% jitter applied to avoid thundering-herd retries when many
 * jobs fail at once (e.g. a downstream dependency outage).
 */
@Component
public class BackoffCalculator {

    private final Duration baseDelay;
    private final Duration maxDelay;

    public BackoffCalculator(
            @Value("${jobqueue.retry.base-delay-ms:1000}") long baseDelayMs,
            @Value("${jobqueue.retry.max-delay-ms:60000}") long maxDelayMs) {
        this.baseDelay = Duration.ofMillis(baseDelayMs);
        this.maxDelay = Duration.ofMillis(maxDelayMs);
    }

    public Duration delayForAttempt(int attemptNumber) {
        if (attemptNumber < 1) {
            throw new IllegalArgumentException("attemptNumber must be >= 1, got " + attemptNumber);
        }
        long exponentialMillis = baseDelay.toMillis() * (1L << Math.min(attemptNumber - 1, 20));
        long cappedMillis = Math.min(exponentialMillis, maxDelay.toMillis());
        double jitterFactor = 0.75 + ThreadLocalRandom.current().nextDouble(0.5); // 0.75x - 1.25x
        return Duration.ofMillis((long) (cappedMillis * jitterFactor));
    }
}
