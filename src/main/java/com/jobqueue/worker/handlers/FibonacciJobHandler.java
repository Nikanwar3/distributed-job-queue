package com.jobqueue.worker.handlers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobqueue.worker.JobHandler;
import org.springframework.stereotype.Component;

import java.math.BigInteger;

/** Sample CPU-bound workload: computes fibonacci(n) for payload {@code {"n": <int>}}. */
@Component
public class FibonacciJobHandler implements JobHandler {

    private final ObjectMapper objectMapper;

    public FibonacciJobHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String type() {
        return "fibonacci";
    }

    @Override
    public String handle(String payload) throws Exception {
        JsonNode node = objectMapper.readTree(payload);
        int n = node.path("n").asInt(0);
        if (n < 0 || n > 100_000) {
            throw new IllegalArgumentException("n must be between 0 and 100000, got " + n);
        }
        BigInteger result = fibonacci(n);
        return objectMapper.writeValueAsString(objectMapper.createObjectNode()
                .put("n", n)
                .put("result", result.toString()));
    }

    private BigInteger fibonacci(int n) {
        BigInteger a = BigInteger.ZERO;
        BigInteger b = BigInteger.ONE;
        for (int i = 0; i < n; i++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new RuntimeException("interrupted during computation");
            }
            BigInteger next = a.add(b);
            a = b;
            b = next;
        }
        return a;
    }
}
