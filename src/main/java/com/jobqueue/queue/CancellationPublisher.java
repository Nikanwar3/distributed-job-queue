package com.jobqueue.queue;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Broadcasts cancellation requests to every running instance. Whichever instance is actually
 * executing the job (if any) will interrupt it; the others ignore the message. This is a
 * best-effort mechanism — see README "Cancellation semantics" for why a stronger guarantee isn't
 * attempted.
 */
@Component
public class CancellationPublisher {

    private final StringRedisTemplate redisTemplate;

    public CancellationPublisher(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void requestCancellation(UUID jobId) {
        redisTemplate.convertAndSend(RedisKeys.CANCELLATION_CHANNEL, jobId.toString());
    }
}
