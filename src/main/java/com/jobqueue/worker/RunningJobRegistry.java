package com.jobqueue.worker;

import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks which jobs this JVM instance is currently executing and on which thread, so that a
 * timeout or a cross-instance cancellation broadcast can interrupt the right thread. A job id only
 * ever appears here on the one instance actually running it.
 */
@Component
public class RunningJobRegistry {

    private final ConcurrentHashMap<UUID, Thread> runningThreads = new ConcurrentHashMap<>();

    public void register(UUID jobId, Thread thread) {
        runningThreads.put(jobId, thread);
    }

    public void unregister(UUID jobId) {
        runningThreads.remove(jobId);
    }

    public boolean interrupt(UUID jobId) {
        Thread thread = runningThreads.get(jobId);
        if (thread == null) {
            return false;
        }
        thread.interrupt();
        return true;
    }

    public boolean isRunningLocally(UUID jobId) {
        return runningThreads.containsKey(jobId);
    }
}
