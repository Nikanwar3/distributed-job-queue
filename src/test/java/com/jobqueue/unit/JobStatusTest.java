package com.jobqueue.unit;

import com.jobqueue.domain.JobStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JobStatusTest {

    @Test
    void onlyCompletedFailedAndCancelledAreTerminal() {
        assertThat(JobStatus.QUEUED.isTerminal()).isFalse();
        assertThat(JobStatus.RUNNING.isTerminal()).isFalse();
        assertThat(JobStatus.COMPLETED.isTerminal()).isTrue();
        assertThat(JobStatus.FAILED.isTerminal()).isTrue();
        assertThat(JobStatus.CANCELLED.isTerminal()).isTrue();
    }
}
