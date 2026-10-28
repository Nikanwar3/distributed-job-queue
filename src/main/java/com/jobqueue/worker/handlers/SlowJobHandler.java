package com.jobqueue.worker.handlers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobqueue.worker.JobHandler;
import org.springframework.stereotype.Component;

/**
 * Sample workload that sleeps for a configurable duration, used to exercise per-job timeout
 * enforcement. Payload: {@code {"sleepMs": <long>}}.
 */
@Component
public class SlowJobHandler implements JobHandler {

    private final ObjectMapper objectMapper;

    public SlowJobHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String type() {
        return "slow";
    }

    @Override
    public String handle(String payload) throws Exception {
        JsonNode node = objectMapper.readTree(payload);
        long sleepMs = node.path("sleepMs").asLong(1000);
        Thread.sleep(sleepMs);
        return objectMapper.writeValueAsString(objectMapper.createObjectNode().put("sleptMs", sleepMs));
    }
}
