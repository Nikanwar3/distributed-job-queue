package com.jobqueue.integration;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Boots the full Spring context against real Postgres and Redis containers.
 *
 * <p>Containers follow the Testcontainers "singleton container" pattern: started once in a static
 * initializer and never explicitly stopped (Testcontainers' Ryuk reaper cleans them up when the JVM
 * exits). This is deliberate — per-class {@code @Container} lifecycle management would restart or
 * tear down the containers between test classes, which is both slow and, for a shared Postgres
 * schema, unsafe. Every integration test class extends this one and inherits the already-running
 * containers and their wired-up connection properties.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES;
    static final GenericContainer<?> REDIS;

    static {
        POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                .withDatabaseName("jobqueue")
                .withUsername("jobqueue")
                .withPassword("jobqueue");
        POSTGRES.start();

        REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                .withExposedPorts(6379);
        REDIS.start();
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        // Fast retry polling so tests don't wait on production-sized intervals. Deliberately NOT
        // overriding recovery-min-idle-ms/recovery-interval-ms here: those control when the
        // recovery scheduler decides a pending message has been abandoned, and every context's
        // scheduler runs continuously for the rest of the suite regardless of which test class is
        // currently executing. A short idle threshold is exactly what AbandonedJobRecoveryIntegration-
        // Test needs to keep its own waits fast — but applied here, it would also apply to every
        // other context, where it caused real jobs that were simply still (legitimately) processing
        // under CI load to be misdiagnosed as abandoned and reprocessed out from under the worker
        // actually running them. That test overrides these two itself, alongside its own namespace.
        registry.add("jobqueue.delayed-retry-interval-ms", () -> "200");
        registry.add("jobqueue.retry.base-delay-ms", () -> "200");
        registry.add("jobqueue.retry.max-delay-ms", () -> "1000");
    }

    @LocalServerPort
    protected int port;

    @Autowired
    protected TestRestTemplate restTemplate;

    protected String baseUrl() {
        return "http://localhost:" + port + "/api/v1/jobs";
    }
}
