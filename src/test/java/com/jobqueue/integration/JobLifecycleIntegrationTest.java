package com.jobqueue.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobqueue.domain.JobStatus;
import com.jobqueue.web.dto.ExecutionHistoryEntry;
import com.jobqueue.web.dto.JobResponse;
import com.jobqueue.web.dto.JobResultResponse;
import com.jobqueue.web.dto.SubmitJobRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class JobLifecycleIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void echoJobRunsThroughToCompletionAndReturnsItsPayloadAsResult() throws Exception {
        var request = new SubmitJobRequest("echo", objectMapper.readTree("{\"hello\":\"world\"}"), null, null, null);

        ResponseEntity<JobResponse> submitResponse = restTemplate.postForEntity(baseUrl(), request, JobResponse.class);
        assertThat(submitResponse.getStatusCode().value()).isEqualTo(201);
        var jobId = submitResponse.getBody().id();
        assertThat(submitResponse.getBody().status()).isEqualTo(JobStatus.QUEUED);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            JobResponse job = restTemplate.getForObject(baseUrl() + "/" + jobId, JobResponse.class);
            assertThat(job.status()).isEqualTo(JobStatus.COMPLETED);
        });

        JobResultResponse result = restTemplate.getForObject(baseUrl() + "/" + jobId + "/result", JobResultResponse.class);
        assertThat(result.status()).isEqualTo(JobStatus.COMPLETED);
        assertThat(objectMapper.readTree(result.result()).get("hello").asText()).isEqualTo("world");

        ResponseEntity<List<ExecutionHistoryEntry>> historyResponse = restTemplate.exchange(
                baseUrl() + "/" + jobId + "/executions",
                org.springframework.http.HttpMethod.GET,
                null,
                new org.springframework.core.ParameterizedTypeReference<>() {});
        assertThat(historyResponse.getBody()).hasSize(1);
        assertThat(historyResponse.getBody().get(0).attemptNumber()).isEqualTo(1);
    }

    @Test
    void returns404ForUnknownJob() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                baseUrl() + "/00000000-0000-0000-0000-000000000000", String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void rejectsSubmissionMissingRequiredFields() {
        var invalid = new SubmitJobRequest("", null, null, null, null);
        ResponseEntity<String> response = restTemplate.postForEntity(baseUrl(), invalid, String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }
}
