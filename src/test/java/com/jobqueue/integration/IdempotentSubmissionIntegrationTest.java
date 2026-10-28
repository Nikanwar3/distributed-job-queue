package com.jobqueue.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobqueue.repository.JobRepository;
import com.jobqueue.web.dto.JobResponse;
import com.jobqueue.web.dto.SubmitJobRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class IdempotentSubmissionIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JobRepository jobRepository;

    @Test
    void concurrentSubmissionsWithTheSameIdempotencyKeyProduceExactlyOneJob() throws Exception {
        String idempotencyKey = "order-" + UUID.randomUUID();
        var request = new SubmitJobRequest("echo", objectMapper.readTree("{}"), idempotencyKey, 0, 5);

        int concurrency = 20;
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        CountDownLatch startLine = new CountDownLatch(1);
        List<AtomicReference<UUID>> results = IntStream.range(0, concurrency)
                .mapToObj(i -> new AtomicReference<UUID>())
                .collect(Collectors.toList());

        for (int i = 0; i < concurrency; i++) {
            int index = i;
            pool.submit(() -> {
                try {
                    startLine.await();
                    ResponseEntity<JobResponse> response = restTemplate.postForEntity(baseUrl(), request, JobResponse.class);
                    results.get(index).set(response.getBody().id());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        startLine.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        Set<UUID> distinctJobIds = results.stream().map(AtomicReference::get).collect(Collectors.toSet());
        assertThat(distinctJobIds).hasSize(1);
        assertThat(jobRepository.findByIdempotencyKey(idempotencyKey)).isPresent();
    }
}
