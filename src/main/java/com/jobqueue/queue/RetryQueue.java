package com.jobqueue.queue;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * A Redis sorted set used as a delayed-visibility queue for scheduled retries: jobs are scored by
 * the epoch-millis timestamp they become due, and {@link #claimDueRetries} atomically removes the
 * jobs it is about to re-publish so that if multiple instances poll concurrently, each due job is
 * handed to exactly one of them.
 */
@Component
public class RetryQueue {

    private final StringRedisTemplate redisTemplate;

    public RetryQueue(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void scheduleRetry(UUID jobId, Instant nextRetryAt) {
        redisTemplate.opsForZSet().add(RedisKeys.DELAYED_RETRY_SET, jobId.toString(), nextRetryAt.toEpochMilli());
    }

    /** Returns the job ids that are due and atomically removes them from the delayed set. */
    public Set<UUID> claimDueRetries(Instant now, int limit) {
        Set<String> due = redisTemplate.opsForZSet()
                .rangeByScore(RedisKeys.DELAYED_RETRY_SET, 0, now.toEpochMilli(), 0, limit);
        if (due == null || due.isEmpty()) {
            return Set.of();
        }
        return due.stream()
                .filter(idStr -> redisTemplate.opsForZSet().remove(RedisKeys.DELAYED_RETRY_SET, idStr) == 1L)
                .map(UUID::fromString)
                .collect(java.util.stream.Collectors.toSet());
    }
}
