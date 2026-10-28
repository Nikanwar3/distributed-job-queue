package com.jobqueue.integration.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobqueue.worker.JobHandler;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test-only handler that fails a configurable number of times before succeeding, keyed by a
 * client-supplied job key in the payload. Lets retry tests assert exact attempt counts instead of
 * relying on randomness.
 */
@Component
public class DeterministicFailThenSucceedHandler implements JobHandler {

    private final ObjectMapper objectMapper;
    private final Map<String, AtomicInteger> attemptsByKey = new ConcurrentHashMap<>();

    public DeterministicFailThenSucceedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String type() {
        return "test-fail-then-succeed";
    }

    @Override
    public String handle(String payload) throws Exception {
        JsonNode node = objectMapper.readTree(payload);
        String key = node.path("key").asText();
        int failuresBeforeSuccess = node.path("failuresBeforeSuccess").asInt(0);

        int attempt = attemptsByKey.computeIfAbsent(key, k -> new AtomicInteger(0)).incrementAndGet();
        if (attempt <= failuresBeforeSuccess) {
            throw new RuntimeException("deliberate failure " + attempt + "/" + failuresBeforeSuccess);
        }
        return objectMapper.writeValueAsString(Map.of("attemptsNeeded", attempt));
    }

    public int attemptsSoFar(String key) {
        return attemptsByKey.getOrDefault(key, new AtomicInteger(0)).get();
    }
}
