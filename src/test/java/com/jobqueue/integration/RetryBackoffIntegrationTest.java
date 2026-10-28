package com.jobqueue.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobqueue.domain.ExecutionStatus;
import com.jobqueue.domain.JobStatus;
import com.jobqueue.integration.support.DeterministicFailThenSucceedHandler;
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

class RetryBackoffIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DeterministicFailThenSucceedHandler handler;

    @Test
    void jobSucceedsAfterExhaustingTransientFailuresWithinRetryBudget() throws Exception {
        String key = "retry-" + UUID.randomUUID();
        var payload = objectMapper.readTree("{\"key\":\"" + key + "\",\"failuresBeforeSuccess\":2}");
        var request = new SubmitJobRequest("test-fail-then-succeed", payload, null, 5, 5);

        ResponseEntity<JobResponse> submitResponse = restTemplate.postForEntity(baseUrl(), request, JobResponse.class);
        UUID jobId = submitResponse.getBody().id();

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            JobResponse job = restTemplate.getForObject(baseUrl() + "/" + jobId, JobResponse.class);
            assertThat(job.status()).isEqualTo(JobStatus.COMPLETED);
        });

        JobResponse finalJob = restTemplate.getForObject(baseUrl() + "/" + jobId, JobResponse.class);
        assertThat(finalJob.retryCount()).isEqualTo(2);

        ResponseEntity<List<ExecutionHistoryEntry>> historyResponse = restTemplate.exchange(
                baseUrl() + "/" + jobId + "/executions", HttpMethod.GET, null,
                new ParameterizedTypeReference<>() {});
        List<ExecutionHistoryEntry> history = historyResponse.getBody();
        assertThat(history).hasSize(3);
        assertThat(history.get(0).status()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(history.get(1).status()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(history.get(2).status()).isEqualTo(ExecutionStatus.SUCCEEDED);
    }

    @Test
    void jobIsMarkedFailedOncePersistentFailuresExhaustTheRetryBudget() throws Exception {
        String key = "exhaust-" + UUID.randomUUID();
        var payload = objectMapper.readTree("{\"key\":\"" + key + "\",\"failuresBeforeSuccess\":100}");
        var request = new SubmitJobRequest("test-fail-then-succeed", payload, null, 2, 5);

        ResponseEntity<JobResponse> submitResponse = restTemplate.postForEntity(baseUrl(), request, JobResponse.class);
        UUID jobId = submitResponse.getBody().id();

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            JobResponse job = restTemplate.getForObject(baseUrl() + "/" + jobId, JobResponse.class);
            assertThat(job.status()).isEqualTo(JobStatus.FAILED);
        });

        JobResponse finalJob = restTemplate.getForObject(baseUrl() + "/" + jobId, JobResponse.class);
        assertThat(finalJob.retryCount()).isEqualTo(2);
        assertThat(finalJob.errorMessage()).contains("deliberate failure");
    }
}
