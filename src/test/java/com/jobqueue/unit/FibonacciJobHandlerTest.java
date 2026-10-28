package com.jobqueue.unit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobqueue.worker.handlers.FibonacciJobHandler;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FibonacciJobHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final FibonacciJobHandler handler = new FibonacciJobHandler(objectMapper);

    @Test
    void computesKnownFibonacciValues() throws Exception {
        String result = handler.handle("{\"n\": 10}");
        JsonNode node = objectMapper.readTree(result);
        assertThat(node.get("result").asText()).isEqualTo("55");
    }

    @Test
    void zeroIsTheBaseCase() throws Exception {
        String result = handler.handle("{\"n\": 0}");
        assertThat(objectMapper.readTree(result).get("result").asText()).isEqualTo("0");
    }

    @Test
    void rejectsOutOfRangeInput() {
        assertThatThrownBy(() -> handler.handle("{\"n\": -1}")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> handler.handle("{\"n\": 999999}")).isInstanceOf(IllegalArgumentException.class);
    }
}
