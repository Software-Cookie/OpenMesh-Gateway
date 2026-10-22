package io.openmesh.gateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.openmesh.gateway.config.OpenMeshProperties;
import io.openmesh.gateway.model.RateLimitResult;
import io.openmesh.gateway.model.TenantContext;
import io.openmesh.gateway.service.RateLimitService;
import io.openmesh.gateway.service.TenantMeteringService;
import io.openmesh.gateway.web.ErrorResponseWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TenantRateLimitFilterTest {

    @Mock
    private RateLimitService rateLimitService;
    @Mock
    private TenantMeteringService metering;

    private TenantRateLimitFilter filter;
    private final AtomicBoolean forwarded = new AtomicBoolean();

    @BeforeEach
    void setUp() {
        filter = new TenantRateLimitFilter(rateLimitService, metering, new ErrorResponseWriter(new ObjectMapper()),
                new OpenMeshProperties());
    }

    private MockServerWebExchange exchange(TenantContext context, Map<String, Object> metadata) {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/orders"));
        Route route = Route.async().id("orders").uri(URI.create("http://orders")).predicate(e -> true)
                .metadata(metadata).build();
        exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR, route);
        exchange.getAttributes().put(ZeroTrustIdentityFilter.TENANT_CONTEXT_ATTR, context);
        return exchange;
    }

    private static TenantContext tenant(String tier) {
        return TenantContext.builder().tenantId("acme").userId("alice").tier(tier)
                .authMethod(TenantContext.AuthMethod.JWT).clientIp("10.0.0.1").build();
    }

    private static TenantContext anonymous() {
        return TenantContext.builder().tenantId("anonymous").userId("anonymous")
                .authMethod(TenantContext.AuthMethod.ANONYMOUS).clientIp("10.0.0.9").build();
    }

    private static RateLimitResult result(boolean allowed, long remaining, long wait, double rate, double burst) {
        return RateLimitResult.builder().allowed(allowed).remainingTokens(remaining).waitOrResetSeconds(wait)
                .replenishRate(rate).burstCapacity(burst).build();
    }

    private Mono<Void> run(MockServerWebExchange exchange) {
        return filter.filter(exchange, e -> {
            forwarded.set(true);
            return Mono.empty();
        });
    }

    @Test
    @DisplayName("Authenticated tenant is limited by its plan tier in a per-tenant bucket")
    void tierLimitsForTenant() {
        when(rateLimitService.checkRateLimit("tenant:acme", 500, 1000, 1)).thenReturn(Mono.just(result(true, 999, 1, 500, 1000)));
        MockServerWebExchange exchange = exchange(tenant("ENTERPRISE"), Map.of());

        StepVerifier.create(run(exchange)).verifyComplete();

        assertThat(forwarded).isTrue();
        HttpHeaders headers = exchange.getResponse().getHeaders();
        assertThat(headers.getFirst(TenantRateLimitFilter.HEADER_LIMIT)).isEqualTo("1000");
        assertThat(headers.getFirst(TenantRateLimitFilter.HEADER_REMAINING)).isEqualTo("999");
        assertThat(headers.getFirst(TenantRateLimitFilter.HEADER_TIER)).isEqualTo("ENTERPRISE");
    }

    @Test
    @DisplayName("Anonymous caller uses the route's limits in a per-route, per-IP bucket")
    void anonymousUsesRouteLimits() {
        when(rateLimitService.checkRateLimit("anon:orders:10.0.0.9", 2, 5, 1)).thenReturn(Mono.just(result(true, 4, 1, 2, 5)));

        StepVerifier.create(run(exchange(anonymous(), Map.of("rateLimit", Map.of("replenishRate", 2, "burstCapacity", 5)))))
                .verifyComplete();

        assertThat(forwarded).isTrue();
    }

    @Test
    @DisplayName("Exhausted bucket returns 429 with Retry-After and records the rejection")
    void rejectsWith429() {
        when(rateLimitService.checkRateLimit(anyString(), anyDouble(), anyDouble(), anyInt()))
                .thenReturn(Mono.just(result(false, 0, 3, 5, 10)));
        MockServerWebExchange exchange = exchange(tenant("FREE"), Map.of());

        StepVerifier.create(run(exchange)).verifyComplete();

        assertThat(forwarded).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(exchange.getResponse().getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("3");
        verify(metering).recordRateLimitRejection(eq("acme"), eq("orders"));
    }

    @Test
    @DisplayName("Fail-closed degraded result returns 503, not 429")
    void failClosedReturns503() {
        when(rateLimitService.checkRateLimit(anyString(), anyDouble(), anyDouble(), anyInt()))
                .thenReturn(Mono.just(RateLimitResult.builder().allowed(false).degraded(true).waitOrResetSeconds(1).build()));
        MockServerWebExchange exchange = exchange(tenant("PRO"), Map.of());

        StepVerifier.create(run(exchange)).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        verify(metering, never()).recordRateLimitRejection(anyString(), anyString());
    }

    @Test
    @DisplayName("Routes can opt out of rate limiting")
    void optOut() {
        StepVerifier.create(run(exchange(tenant("FREE"), Map.of("rateLimit", Map.of("enabled", false))))).verifyComplete();

        assertThat(forwarded).isTrue();
        verify(rateLimitService, never()).checkRateLimit(anyString(), anyDouble(), anyDouble(), anyInt());
    }
}
