package com.jobqueue.worker.handlers;

import com.jobqueue.worker.JobHandler;
import org.springframework.stereotype.Component;

/** Trivial handler used for smoke-testing the platform end to end: returns the payload unchanged. */
@Component
public class EchoJobHandler implements JobHandler {

    @Override
    public String type() {
        return "echo";
    }

    @Override
    public String handle(String payload) {
        return payload;
    }
}
