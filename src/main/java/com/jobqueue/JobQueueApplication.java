package com.jobqueue;

import com.jobqueue.config.JobQueueProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(JobQueueProperties.class)
public class JobQueueApplication {

    public static void main(String[] args) {
        SpringApplication.run(JobQueueApplication.class, args);
    }
}
