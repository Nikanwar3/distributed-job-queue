package com.jobqueue.service;

public enum CancellationOutcome {
    /** Job was still queued; it was cancelled synchronously and will never execute. */
    CANCELLED_IMMEDIATELY,
    /** Job was running; a best-effort interrupt was broadcast but completion isn't guaranteed. */
    CANCELLATION_REQUESTED
}
