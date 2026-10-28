package com.jobqueue.domain;

/** Outcome of a single execution attempt, recorded for history/audit purposes. */
public enum ExecutionStatus {
    STARTED,
    SUCCEEDED,
    FAILED,
    TIMED_OUT
}
