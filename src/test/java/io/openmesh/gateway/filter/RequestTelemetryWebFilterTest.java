package io.openmesh.gateway.filter;

import io.openmesh.gateway.service.TenantMeteringService;
import io.openmesh.gateway.web.TraceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RequestTelemetryWebFilterTest {

    @Mock
    private TenantMeteringService metering;

    private ServerWebExchange run(MockServerHttpRequest request) {
        RequestTelemetryWebFilter filter = new RequestTelemetryWebFilter(metering);
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        AtomicReference<ServerWebExchange> seen = new AtomicReference<>();
        StepVerifier.create(filter.filter(exchange, e -> {
            seen.set(e);
            e.getResponse().setStatusCode(HttpStatus.OK);
            return Mono.empty();
        })).verifyComplete();
        return seen.get();
    }

    @Test
    @DisplayName("Continues an incoming W3C trace and starts a new span")
    void continuesTrace() {
        String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";
        ServerWebExchange e = run(MockServerHttpRequest.get("/orders")
                .header("traceparent", "00-" + traceId + "-00f067aa0ba902b7-01").build());

        String forwarded = e.getRequest().getHeaders().getFirst(TraceContext.TRACEPARENT_HEADER);
        assertThat(forwarded).startsWith("00-" + traceId + "-").endsWith("-01").doesNotContain("00f067aa0ba902b7");
        assertThat(e.getResponse().getHeaders().getFirst(TraceContext.TRACE_ID_HEADER)).isEqualTo(traceId);
        verify(metering).recordRequest(eq("anonymous"), eq("unmatched"), eq("GET"), eq(200), any());
    }

    @Test
    @DisplayName("Replaces a malformed traceparent and an unsafe request id")
    void replacesInvalidInput() {
        ServerWebExchange e = run(MockServerHttpRequest.get("/orders")
                .header("traceparent", "garbage")
                .header("X-Request-Id", "<script>").build());

        assertThat(e.getRequest().getHeaders().getFirst(TraceContext.TRACEPARENT_HEADER)).matches("^00-[0-9a-f]{32}-[0-9a-f]{16}-01$");
        assertThat(e.getRequest().getHeaders().getFirst(TraceContext.REQUEST_ID_HEADER)).matches("^[0-9a-f]{16}$");
    }

    @Test
    @DisplayName("Admin and actuator calls are not metered as tenant traffic")
    void skipsInternalPaths() {
        run(MockServerHttpRequest.get("/admin/v1/routes").build());
        run(MockServerHttpRequest.get("/actuator/health").build());

        verify(metering, never()).recordRequest(anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.anyInt(), any());
    }
}
