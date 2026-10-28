package com.jobqueue.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Central configuration for queueing, worker concurrency, and defaults applied to new jobs. */
@ConfigurationProperties(prefix = "jobqueue")
public class JobQueueProperties {

    /** Whether this instance runs the consumer loop that executes jobs. */
    private boolean workerEnabled = true;

    /** Max jobs this instance will execute concurrently. */
    private int workerConcurrency = 4;

    /** Number of stream entries requested per XREADGROUP call. */
    private int consumerBatchSize = 10;

    /** Block duration (ms) for XREADGROUP when the stream is empty. */
    private long consumerBlockMs = 5000;

    /** Default max retries applied to a job when the client doesn't specify one. */
    private int defaultMaxRetries = 3;

    /** Default timeout (seconds) applied to a job when the client doesn't specify one. */
    private int defaultTimeoutSeconds = 30;

    /** How often the recovery scheduler scans for abandoned (crashed-worker) messages. */
    private long recoveryIntervalMs = 15000;

    /** Minimum idle time (ms) before a pending stream entry is considered abandoned. */
    private long recoveryMinIdleMs = 60000;

    /** How often the delayed-retry scheduler polls for due retries. */
    private long delayedRetryIntervalMs = 1000;

    public boolean isWorkerEnabled() {
        return workerEnabled;
    }

    public void setWorkerEnabled(boolean workerEnabled) {
        this.workerEnabled = workerEnabled;
    }

    public int getWorkerConcurrency() {
        return workerConcurrency;
    }

    public void setWorkerConcurrency(int workerConcurrency) {
        this.workerConcurrency = workerConcurrency;
    }

    public int getConsumerBatchSize() {
        return consumerBatchSize;
    }

    public void setConsumerBatchSize(int consumerBatchSize) {
        this.consumerBatchSize = consumerBatchSize;
    }

    public long getConsumerBlockMs() {
        return consumerBlockMs;
    }

    public void setConsumerBlockMs(long consumerBlockMs) {
        this.consumerBlockMs = consumerBlockMs;
    }

    public int getDefaultMaxRetries() {
        return defaultMaxRetries;
    }

    public void setDefaultMaxRetries(int defaultMaxRetries) {
        this.defaultMaxRetries = defaultMaxRetries;
    }

    public int getDefaultTimeoutSeconds() {
        return defaultTimeoutSeconds;
    }

    public void setDefaultTimeoutSeconds(int defaultTimeoutSeconds) {
        this.defaultTimeoutSeconds = defaultTimeoutSeconds;
    }

    public long getRecoveryIntervalMs() {
        return recoveryIntervalMs;
    }

    public void setRecoveryIntervalMs(long recoveryIntervalMs) {
        this.recoveryIntervalMs = recoveryIntervalMs;
    }

    public long getRecoveryMinIdleMs() {
        return recoveryMinIdleMs;
    }

    public void setRecoveryMinIdleMs(long recoveryMinIdleMs) {
        this.recoveryMinIdleMs = recoveryMinIdleMs;
    }

    public long getDelayedRetryIntervalMs() {
        return delayedRetryIntervalMs;
    }

    public void setDelayedRetryIntervalMs(long delayedRetryIntervalMs) {
        this.delayedRetryIntervalMs = delayedRetryIntervalMs;
    }
}
