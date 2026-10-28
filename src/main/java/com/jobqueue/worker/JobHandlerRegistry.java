package com.jobqueue.worker;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Looks up the {@link JobHandler} registered for a job's type. */
@Component
public class JobHandlerRegistry {

    private final Map<String, JobHandler> handlersByType;

    public JobHandlerRegistry(List<JobHandler> handlers) {
        this.handlersByType = handlers.stream()
                .collect(Collectors.toMap(JobHandler::type, Function.identity()));
    }

    public Optional<JobHandler> find(String type) {
        return Optional.ofNullable(handlersByType.get(type));
    }
}
