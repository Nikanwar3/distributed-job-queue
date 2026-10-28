package com.jobqueue.web.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record SubmitJobRequest(

        @NotBlank(message = "type is required")
        @Size(max = 100)
        String type,

        @NotNull(message = "payload is required (use {} for an empty payload)")
        JsonNode payload,

        @Size(max = 255)
        String idempotencyKey,

        @Min(value = 0, message = "maxRetries must not be negative")
        @Max(value = 20, message = "maxRetries must not exceed 20")
        Integer maxRetries,

        @Min(value = 1, message = "timeoutSeconds must be at least 1")
        @Max(value = 3600, message = "timeoutSeconds must not exceed 3600")
        Integer timeoutSeconds
) {
}
