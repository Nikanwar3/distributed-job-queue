package com.jobqueue.domain;

/**
 * Lifecycle states for a submitted job.
 *
 * <pre>
 *   QUEUED --&gt; RUNNING --&gt; COMPLETED
 *      |          |
 *      |          +--&gt; QUEUED (retry scheduled)
 *      |          +--&gt; FAILED (retries exhausted)
 *      +--&gt; CANCELLED
 *                 |
 *      RUNNING ----&gt; CANCELLED (best-effort, cooperative)
 * </pre>
 */
public enum JobStatus {
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
}
