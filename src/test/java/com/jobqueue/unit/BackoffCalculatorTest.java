package com.jobqueue.unit;

import com.jobqueue.retry.BackoffCalculator;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BackoffCalculatorTest {

    private final BackoffCalculator calculator = new BackoffCalculator(1000, 60000);

    @Test
    void growsExponentiallyWithinJitterBounds() {
        Duration attempt1 = calculator.delayForAttempt(1);
        Duration attempt2 = calculator.delayForAttempt(2);
        Duration attempt3 = calculator.delayForAttempt(3);

        // base=1000ms, jitter 0.75x-1.25x: attempt1 in [750,1250], attempt2 in [1500,2500], attempt3 in [3000,5000]
        assertThat(attempt1.toMillis()).isBetween(750L, 1250L);
        assertThat(attempt2.toMillis()).isBetween(1500L, 2500L);
        assertThat(attempt3.toMillis()).isBetween(3000L, 5000L);
    }

    @Test
    void neverExceedsMaxDelayEvenAtHighAttemptCounts() {
        Duration delay = calculator.delayForAttempt(50);
        assertThat(delay.toMillis()).isLessThanOrEqualTo((long) (60000 * 1.25));
    }

    @Test
    void rejectsNonPositiveAttemptNumbers() {
        assertThatThrownBy(() -> calculator.delayForAttempt(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calculator.delayForAttempt(-1)).isInstanceOf(IllegalArgumentException.class);
    }
}
