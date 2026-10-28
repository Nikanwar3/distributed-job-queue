package com.jobqueue.worker;

import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.UUID;

/** Stable identifier for this process, used as the Redis Streams consumer name and in logs/metrics. */
@Component
public class WorkerIdentity {

    private final String id;

    public WorkerIdentity() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            host = "unknown-host";
        }
        this.id = host + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    public String id() {
        return id;
    }
}
