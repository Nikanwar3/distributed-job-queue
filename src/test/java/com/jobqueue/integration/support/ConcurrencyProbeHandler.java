package com.jobqueue.integration.support;

import com.jobqueue.worker.JobHandler;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test-only handler that records how many instances of itself are executing concurrently, so tests
 * can assert the worker pool never exceeds its configured concurrency and actually uses it.
 */
@Component
public class ConcurrencyProbeHandler implements JobHandler {

    private final AtomicInteger current = new AtomicInteger(0);
    private final AtomicInteger max = new AtomicInteger(0);

    @Override
    public String type() {
        return "test-concurrency-probe";
    }

    @Override
    public String handle(String payload) throws Exception {
        int inFlight = current.incrementAndGet();
        max.updateAndGet(prev -> Math.max(prev, inFlight));
        try {
            Thread.sleep(500);
            return "{}";
        } finally {
            current.decrementAndGet();
        }
    }

    public int maxObservedConcurrency() {
        return max.get();
    }

    public void reset() {
        current.set(0);
        max.set(0);
    }
}
