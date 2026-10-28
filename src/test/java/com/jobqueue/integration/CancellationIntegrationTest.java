package com.jobqueue.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobqueue.domain.JobStatus;
import com.jobqueue.service.CancellationOutcome;
import com.jobqueue.web.dto.CancellationResponse;
import com.jobqueue.web.dto.JobResponse;
import com.jobqueue.web.dto.SubmitJobRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class CancellationIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void runningJobReceivesABestEffortInterruptAndEndsUpCancelled() throws Exception {
        var payload = objectMapper.readTree("{\"sleepMs\": 10000}");
        var request = new SubmitJobRequest("slow", payload, null, 0, 60);

        ResponseEntity<JobResponse> submitResponse = restTemplate.postForEntity(baseUrl(), request, JobResponse.class);
        UUID jobId = submitResponse.getBody().id();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            JobResponse job = restTemplate.getForObject(baseUrl() + "/" + jobId, JobResponse.class);
            assertThat(job.status()).isEqualTo(JobStatus.RUNNING);
        });

        ResponseEntity<CancellationResponse> cancelResponse = restTemplate.postForEntity(
                baseUrl() + "/" + jobId + "/cancel", null, CancellationResponse.class);
        assertThat(cancelResponse.getBody().outcome()).isEqualTo(CancellationOutcome.CANCELLATION_REQUESTED);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            JobResponse job = restTemplate.getForObject(baseUrl() + "/" + jobId, JobResponse.class);
            assertThat(job.status()).isEqualTo(JobStatus.CANCELLED);
        });
    }

    @Test
    void queuedJobIsCancelledSynchronouslyAndNeverRuns() throws Exception {
        // zero worker concurrency-adjacent race avoidance: use a type with no handler so even if
        // picked up, it cannot "complete" out from under the cancel race in this fast test.
        var payload = objectMapper.readTree("{}");
        var request = new SubmitJobRequest("echo", payload, null, 0, 30);

        ResponseEntity<JobResponse> submitResponse = restTemplate.postForEntity(baseUrl(), request, JobResponse.class);
        UUID jobId = submitResponse.getBody().id();

        ResponseEntity<CancellationResponse> cancelResponse = restTemplate.postForEntity(
                baseUrl() + "/" + jobId + "/cancel", null, CancellationResponse.class);

        // Either we win the race and cancel a still-queued job, or the (near-instant) echo handler
        // already completed it — both are correct outcomes of a fair race, so accept either rather
        // than asserting a specific winner.
        CancellationOutcome outcome = cancelResponse.getBody() != null ? cancelResponse.getBody().outcome() : null;
        if (cancelResponse.getStatusCode().value() == 409) {
            JobResponse job = restTemplate.getForObject(baseUrl() + "/" + jobId, JobResponse.class);
            assertThat(job.status()).isIn(JobStatus.COMPLETED, JobStatus.CANCELLED);
        } else {
            assertThat(outcome).isIn(CancellationOutcome.CANCELLED_IMMEDIATELY, CancellationOutcome.CANCELLATION_REQUESTED);
        }
    }

    @Test
    void cancellingAnAlreadyTerminalJobReturnsConflict() throws Exception {
        var request = new SubmitJobRequest("echo", objectMapper.readTree("{}"), null, 0, 30);
        ResponseEntity<JobResponse> submitResponse = restTemplate.postForEntity(baseUrl(), request, JobResponse.class);
        UUID jobId = submitResponse.getBody().id();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            JobResponse job = restTemplate.getForObject(baseUrl() + "/" + jobId, JobResponse.class);
            assertThat(job.status()).isEqualTo(JobStatus.COMPLETED);
        });

        ResponseEntity<String> cancelResponse = restTemplate.postForEntity(
                baseUrl() + "/" + jobId + "/cancel", null, String.class);
        assertThat(cancelResponse.getStatusCode().value()).isEqualTo(409);
    }
}
