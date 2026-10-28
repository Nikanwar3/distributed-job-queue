package com.jobqueue.worker;

/**
 * Executes the business logic for one job {@link #type()}. Implementations should be idempotent
 * where possible, since the platform guarantees at-least-once delivery of the execution attempt,
 * not exactly-once (see README "Delivery guarantees").
 *
 * <p>Handlers run on a worker thread that may be interrupted if the job's timeout elapses or a
 * cancellation is requested; well-behaved handlers should check {@link Thread#isInterrupted()}
 * during long-running loops and exit promptly.
 */
public interface JobHandler {

    String type();

    /**
     * @param payload raw JSON payload supplied at submission time
     * @return a JSON-serializable result string stored against the job
     * @throws Exception any failure; the platform will apply the configured retry policy
     */
    String handle(String payload) throws Exception;
}
