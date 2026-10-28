package com.jobqueue.queue;

/** Central registry of Redis key names so producer, workers, and recovery jobs stay in sync. */
public final class RedisKeys {

    /** Stream carrying job-ready notifications: field {@code jobId} -> UUID string. */
    public static final String JOB_STREAM = "jobqueue:stream";

    /** Consumer group all worker instances share; each instance uses a distinct consumer name. */
    public static final String CONSUMER_GROUP = "jobqueue:workers";

    /** Sorted set of jobIds awaiting a scheduled retry, scored by epoch-millis of next attempt. */
    public static final String DELAYED_RETRY_SET = "jobqueue:delayed";

    /** Pub/Sub channel used to broadcast best-effort cancellation requests to all instances. */
    public static final String CANCELLATION_CHANNEL = "jobqueue:cancel";

    private RedisKeys() {
    }
}
