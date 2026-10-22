package io.openmesh.gateway.controller;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.openmesh.gateway.model.ApiError;
import io.openmesh.gateway.web.TraceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.Set;
import java.util.concurrent.TimeoutException;

/**
 * Circuit-breaker fallback. Routes forward here when the downstream call fails, times out or
 * the breaker is open. The response explains which of those happened.
 */
@Slf4j
@RestController
@RequestMapping("/fallback")
public class FallbackController {

    @RequestMapping({"/service-unavailable", "/default"})
    public Mono<ResponseEntity<ApiError>> serviceUnavailable(ServerWebExchange exchange) {
        Throwable cause = exchange.getAttribute(ServerWebExchangeUtils.CIRCUITBREAKER_EXECUTION_EXCEPTION_ATTR);
        return Mono.just(respond(exchange, cause));
    }

    @RequestMapping("/timeout")
    public Mono<ResponseEntity<ApiError>> timeout(ServerWebExchange exchange) {
        return Mono.just(build(exchange, HttpStatus.GATEWAY_TIMEOUT, "UPSTREAM_TIMEOUT",
                "The upstream service did not respond in time", 5));
    }

    private ResponseEntity<ApiError> respond(ServerWebExchange exchange, Throwable cause) {
        if (cause != null) {
            log.warn("Fallback for {} (trace {}): {}", originalPath(exchange), TraceContext.traceId(exchange), cause.toString());
        }
        if (cause instanceof TimeoutException) {
            return build(exchange, HttpStatus.GATEWAY_TIMEOUT, "UPSTREAM_TIMEOUT",
                    "The upstream service did not respond in time", 5);
        }
        if (cause instanceof CallNotPermittedException) {
            return build(exchange, HttpStatus.SERVICE_UNAVAILABLE, "CIRCUIT_OPEN",
                    "The upstream service is failing; requests are paused to let it recover", 10);
        }
        // Raised when the route's statusCodes list (e.g. 500, 502) matches the upstream response
        if (cause instanceof HttpStatusCodeException) {
            return build(exchange, HttpStatus.BAD_GATEWAY, "UPSTREAM_ERROR",
                    "The upstream service returned an error", 5);
        }
        return build(exchange, HttpStatus.SERVICE_UNAVAILABLE, "UPSTREAM_UNAVAILABLE",
                "The upstream service is unavailable", 10);
    }

    private ResponseEntity<ApiError> build(ServerWebExchange exchange, HttpStatus status, String reason,
                                           String message, int retryAfter) {
        ApiError body = ApiError.builder()
                .status(status.value())
                .error(status.getReasonPhrase())
                .reason(reason)
                .message(message)
                .path(originalPath(exchange))
                .traceId(TraceContext.traceId(exchange))
                .retryAfterSeconds(retryAfter)
                .build();
        return ResponseEntity.status(status)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter))
                .body(body);
    }

    /** The client's original path, not the internal /fallback path. */
    private static String originalPath(ServerWebExchange exchange) {
        Set<URI> urls = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ORIGINAL_REQUEST_URL_ATTR);
        if (urls != null && !urls.isEmpty()) {
            return urls.iterator().next().getPath();
        }
        return exchange.getRequest().getPath().value();
    }
}
