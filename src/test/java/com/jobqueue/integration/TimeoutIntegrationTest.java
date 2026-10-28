package com.jobqueue.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobqueue.domain.ExecutionStatus;
import com.jobqueue.domain.JobStatus;
import com.jobqueue.web.dto.ExecutionHistoryEntry;
import com.jobqueue.web.dto.JobResponse;
import com.jobqueue.web.dto.SubmitJobRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class TimeoutIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void jobExceedingItsTimeoutIsInterruptedAndFailedAfterRetriesExhaust() throws Exception {
        // sleeps far longer than its 1s timeout; zero retries so it fails deterministically fast.
        var payload = objectMapper.readTree("{\"sleepMs\": 10000}");
        var request = new SubmitJobRequest("slow", payload, null, 0, 1);

        ResponseEntity<JobResponse> submitResponse = restTemplate.postForEntity(baseUrl(), request, JobResponse.class);
        UUID jobId = submitResponse.getBody().id();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            JobResponse job = restTemplate.getForObject(baseUrl() + "/" + jobId, JobResponse.class);
            assertThat(job.status()).isEqualTo(JobStatus.FAILED);
        });

        JobResponse finalJob = restTemplate.getForObject(baseUrl() + "/" + jobId, JobResponse.class);
        assertThat(finalJob.errorMessage()).contains("timeout");

        ResponseEntity<List<ExecutionHistoryEntry>> historyResponse = restTemplate.exchange(
                baseUrl() + "/" + jobId + "/executions", HttpMethod.GET, null,
                new ParameterizedTypeReference<>() {});
        assertThat(historyResponse.getBody()).hasSize(1);
        assertThat(historyResponse.getBody().get(0).status()).isEqualTo(ExecutionStatus.TIMED_OUT);
    }
}
