package com.jobqueue.unit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobqueue.worker.handlers.FlakyJobHandler;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FlakyJobHandlerTest {

    private final FlakyJobHandler handler = new FlakyJobHandler(new ObjectMapper());

    @Test
    void neverFailsWhenFailureRateIsZero() {
        assertThatCode(() -> handler.handle("{\"failureRate\": 0.0}")).doesNotThrowAnyException();
    }

    @Test
    void alwaysFailsWhenFailureRateIsOne() {
        assertThatThrownBy(() -> handler.handle("{\"failureRate\": 1.0}")).isInstanceOf(RuntimeException.class);
    }

    @Test
    void defaultsToRoughlyFiftyPercentWhenUnspecified() {
        int successes = 0;
        for (int i = 0; i < 200; i++) {
            try {
                handler.handle("{}");
                successes++;
            } catch (Exception ignored) {
                // expected roughly half the time
            }
        }
        assertThat(successes).isBetween(40, 160);
    }
}
