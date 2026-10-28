package com.jobqueue.queue;

import com.jobqueue.worker.RunningJobRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Receives cancellation broadcasts and interrupts the job locally if this instance is running it. */
@Component
public class CancellationSubscriber implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(CancellationSubscriber.class);

    private final RunningJobRegistry runningJobRegistry;

    public CancellationSubscriber(RunningJobRegistry runningJobRegistry) {
        this.runningJobRegistry = runningJobRegistry;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        UUID jobId = UUID.fromString(new String(message.getBody(), StandardCharsets.UTF_8));
        if (runningJobRegistry.interrupt(jobId)) {
            log.info("Interrupted locally running job {} in response to cancellation broadcast", jobId);
        }
    }
}
