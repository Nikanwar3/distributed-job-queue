package com.jobqueue.queue;

import com.jobqueue.config.JobQueueProperties;
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
    private final JobQueueProperties properties;

    public CancellationPublisher(StringRedisTemplate redisTemplate, JobQueueProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    public void requestCancellation(UUID jobId) {
        redisTemplate.convertAndSend(properties.getCancellationChannel(), jobId.toString());
    }
}
