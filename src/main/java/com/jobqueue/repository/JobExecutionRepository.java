package com.jobqueue.repository;

import com.jobqueue.domain.JobExecution;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JobExecutionRepository extends JpaRepository<JobExecution, UUID> {

    List<JobExecution> findByJobIdOrderByAttemptNumberAsc(UUID jobId);

    Optional<JobExecution> findFirstByJobIdAndFinishedAtIsNullOrderByAttemptNumberDesc(UUID jobId);
}
