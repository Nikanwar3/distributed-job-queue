package com.jobqueue.repository;

import com.jobqueue.domain.Job;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;
import java.util.UUID;

public interface JobRepository extends JpaRepository<Job, UUID>, JpaSpecificationExecutor<Job> {

    Optional<Job> findByIdempotencyKey(String idempotencyKey);
}
