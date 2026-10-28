package com.jobqueue.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobqueue.domain.JobStatus;
import com.jobqueue.integration.support.ConcurrencyProbeHandler;
import com.jobqueue.web.dto.JobResponse;
import com.jobqueue.web.dto.SubmitJobRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Verifies the worker pool's concurrency cap is both respected (never exceeded) and actually used
 * (reaches the configured bound rather than silently serializing work).
 */
class BoundedConcurrencyIntegrationTest extends AbstractIntegrationTest {

    private static final int CONCURRENCY_LIMIT = 2;

    @DynamicPropertySource
    static void overrideConcurrency(DynamicPropertyRegistry registry) {
        registry.add("jobqueue.worker-concurrency", () -> String.valueOf(CONCURRENCY_LIMIT));
        // Distinct property set => distinct (uncached) Spring context => its own live JobWorker
        // running alongside the other test classes' contexts for the rest of the suite. Without its
        // own namespace, that worker would compete with other contexts' workers over the same
        // Redis consumer group and could "steal" jobs meant for this test (or vice versa),
        // undercounting the observed concurrency.
        registry.add("jobqueue.namespace", () -> "jobqueue-test-bounded-concurrency");
    }

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ConcurrencyProbeHandler probeHandler;

    @BeforeEach
    void resetProbe() {
        probeHandler.reset();
    }

    @Test
    void neverExceedsConfiguredWorkerConcurrencyButDoesReachIt() throws Exception {
        int jobCount = CONCURRENCY_LIMIT * 4;
        List<UUID> jobIds = new ArrayList<>();
        for (int i = 0; i < jobCount; i++) {
            var request = new SubmitJobRequest("test-concurrency-probe", objectMapper.readTree("{}"), null, 0, 30);
            ResponseEntity<JobResponse> response = restTemplate.postForEntity(baseUrl(), request, JobResponse.class);
            jobIds.add(response.getBody().id());
        }

        for (UUID jobId : jobIds) {
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                JobResponse job = restTemplate.getForObject(baseUrl() + "/" + jobId, JobResponse.class);
                assertThat(job.status()).isEqualTo(JobStatus.COMPLETED);
            });
        }

        assertThat(probeHandler.maxObservedConcurrency())
                .as("worker pool should parallelize up to its configured limit")
                .isEqualTo(CONCURRENCY_LIMIT);
    }
}
