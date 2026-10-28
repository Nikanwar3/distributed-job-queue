package com.jobqueue.worker.handlers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobqueue.worker.JobHandler;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Sample workload that fails with the given probability, used to exercise the retry/backoff
 * machinery. Payload: {@code {"failureRate": 0.0-1.0}} (default 0.5).
 */
@Component
public class FlakyJobHandler implements JobHandler {

    private final ObjectMapper objectMapper;

    public FlakyJobHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String type() {
        return "flaky";
    }

    @Override
    public String handle(String payload) throws Exception {
        JsonNode node = objectMapper.readTree(payload);
        double failureRate = node.path("failureRate").asDouble(0.5);
        if (ThreadLocalRandom.current().nextDouble() < failureRate) {
            throw new RuntimeException("simulated transient failure (failureRate=" + failureRate + ")");
        }
        return objectMapper.writeValueAsString(objectMapper.createObjectNode().put("status", "succeeded"));
    }
}
