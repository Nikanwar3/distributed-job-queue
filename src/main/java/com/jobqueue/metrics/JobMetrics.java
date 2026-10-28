package com.jobqueue.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/** Centralizes the Micrometer instrumentation emitted across submission, execution, and recovery. */
@Component
public class JobMetrics {

    private final Counter submittedCounter;
    private final Counter completedCounter;
    private final Counter failedCounter;
    private final Counter cancelledCounter;
    private final Counter retriedCounter;
    private final Counter recoveredCounter;
    private final Counter duplicateDeliverySkippedCounter;
    private final Timer queueWaitTimer;
    private final Timer executionTimer;

    public JobMetrics(MeterRegistry registry) {
        this.submittedCounter = Counter.builder("jobqueue.jobs.submitted").register(registry);
        this.completedCounter = Counter.builder("jobqueue.jobs.completed").register(registry);
        this.failedCounter = Counter.builder("jobqueue.jobs.failed").register(registry);
        this.cancelledCounter = Counter.builder("jobqueue.jobs.cancelled").register(registry);
        this.retriedCounter = Counter.builder("jobqueue.jobs.retried").register(registry);
        this.recoveredCounter = Counter.builder("jobqueue.jobs.recovered").register(registry);
        this.duplicateDeliverySkippedCounter = Counter.builder("jobqueue.jobs.duplicate_delivery_skipped").register(registry);
        this.queueWaitTimer = Timer.builder("jobqueue.queue.wait_time").register(registry);
        this.executionTimer = Timer.builder("jobqueue.execution.duration").register(registry);
    }

    public void jobSubmitted() {
        submittedCounter.increment();
    }

    public void jobCompleted() {
        completedCounter.increment();
    }

    public void jobFailed() {
        failedCounter.increment();
    }

    public void jobCancelled() {
        cancelledCounter.increment();
    }

    public void jobRetried() {
        retriedCounter.increment();
    }

    public void jobRecovered() {
        recoveredCounter.increment();
    }

    public void duplicateDeliverySkipped() {
        duplicateDeliverySkippedCounter.increment();
    }

    public void recordQueueWait(Duration duration) {
        queueWaitTimer.record(duration.toMillis(), TimeUnit.MILLISECONDS);
    }

    public void recordExecutionDuration(Duration duration) {
        executionTimer.record(duration.toMillis(), TimeUnit.MILLISECONDS);
    }
}
