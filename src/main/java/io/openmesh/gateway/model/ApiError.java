package io.openmesh.gateway.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * The single error body returned by the gateway for every failure
 * (401, 403, 404, 409, 429, 502, 503, 504, validation errors).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiError {
    @Builder.Default
    private String timestamp = Instant.now().toString();
    private int status;
    private String error;
    private String message;
    private String path;
    private String traceId;
    /** Machine-readable cause, e.g. RATE_LIMITED, CIRCUIT_OPEN, UPSTREAM_TIMEOUT. */
    private String reason;
    private Integer retryAfterSeconds;
    private List<String> details;
}
