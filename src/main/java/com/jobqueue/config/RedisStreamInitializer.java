package com.jobqueue.config;

import com.jobqueue.queue.RedisKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Ensures the job stream and its consumer group exist before any producer or worker touches them.
 * Idempotent: running this against an already-initialized stream is a harmless no-op (Redis
 * reports BUSYGROUP, which we swallow).
 */
@Component
public class RedisStreamInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RedisStreamInitializer.class);

    private final StringRedisTemplate redisTemplate;

    public RedisStreamInitializer(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            redisTemplate.opsForStream().createGroup(RedisKeys.JOB_STREAM, ReadOffset.from("0"), RedisKeys.CONSUMER_GROUP);
            log.info("Created consumer group '{}' on stream '{}'", RedisKeys.CONSUMER_GROUP, RedisKeys.JOB_STREAM);
        } catch (RedisSystemException e) {
            if (e.getCause() != null && e.getCause().getMessage() != null
                    && e.getCause().getMessage().contains("BUSYGROUP")) {
                log.debug("Consumer group '{}' already exists", RedisKeys.CONSUMER_GROUP);
            } else {
                throw e;
            }
        }
    }
}
