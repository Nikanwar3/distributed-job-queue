package com.jobqueue.queue;

import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/** Publishes job-ready notifications onto the Redis stream for workers to pick up. */
@Component
public class JobQueuePublisher {

    private final StringRedisTemplate redisTemplate;

    public JobQueuePublisher(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public RecordId publish(UUID jobId) {
        MapRecord<String, String, String> record = MapRecord.create(RedisKeys.JOB_STREAM, Map.of("jobId", jobId.toString()));
        return redisTemplate.<String, String>opsForStream().add(record);
    }
}
