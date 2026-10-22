package io.openmesh.gateway.controller;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.openmesh.gateway.model.ApiError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.client.HttpServerErrorException;
import reactor.test.StepVerifier;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

class FallbackControllerTest {

    private final FallbackController controller = new FallbackController();

    private void assertFallback(Throwable cause, HttpStatus status, String reason) {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/fallback/service-unavailable"));
        Set<URI> original = new LinkedHashSet<>();
        original.add(URI.create("http://gateway/service-beta/orders"));
        exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ORIGINAL_REQUEST_URL_ATTR, original);
        if (cause != null) {
            exchange.getAttributes().put(ServerWebExchangeUtils.CIRCUITBREAKER_EXECUTION_EXCEPTION_ATTR, cause);
        }

        StepVerifier.create(controller.serviceUnavailable(exchange))
                .assertNext(res -> {
                    assertThat(res.getStatusCode()).isEqualTo(status);
                    ApiError body = res.getBody();
                    assertThat(body.getReason()).isEqualTo(reason);
                    assertThat(body.getPath()).isEqualTo("/service-beta/orders");
                    assertThat(res.getHeaders().getFirst("Retry-After")).isNotBlank();
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Timeout becomes 504 UPSTREAM_TIMEOUT")
    void timeout() {
        assertFallback(new TimeoutException(), HttpStatus.GATEWAY_TIMEOUT, "UPSTREAM_TIMEOUT");
    }

    @Test
    @DisplayName("Open circuit becomes 503 CIRCUIT_OPEN")
    void circuitOpen() {
        assertFallback(CallNotPermittedException.createCallNotPermittedException(CircuitBreaker.ofDefaults("beta")),
                HttpStatus.SERVICE_UNAVAILABLE, "CIRCUIT_OPEN");
    }

    @Test
    @DisplayName("Upstream 5xx becomes 502 UPSTREAM_ERROR")
    void upstreamError() {
        assertFallback(new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR), HttpStatus.BAD_GATEWAY, "UPSTREAM_ERROR");
    }

    @Test
    @DisplayName("Connection failures become 503 UPSTREAM_UNAVAILABLE")
    void unavailable() {
        assertFallback(new java.net.ConnectException("refused"), HttpStatus.SERVICE_UNAVAILABLE, "UPSTREAM_UNAVAILABLE");
    }
}
