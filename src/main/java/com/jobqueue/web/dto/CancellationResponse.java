package com.jobqueue.web.dto;

import com.jobqueue.service.CancellationOutcome;

import java.util.UUID;

public record CancellationResponse(UUID id, CancellationOutcome outcome, String message) {

    public static CancellationResponse of(UUID id, CancellationOutcome outcome) {
        String message = switch (outcome) {
            case CANCELLED_IMMEDIATELY -> "Job was queued and has been cancelled; it will not run.";
            case CANCELLATION_REQUESTED -> "Job is running; a cancellation signal was sent. "
                    + "Stopping is best-effort and not guaranteed to be immediate.";
        };
        return new CancellationResponse(id, outcome, message);
    }
}
