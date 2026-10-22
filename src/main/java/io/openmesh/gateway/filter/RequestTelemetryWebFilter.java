package io.openmesh.gateway.filter;

import io.openmesh.gateway.model.TenantContext;
import io.openmesh.gateway.service.TenantMeteringService;
import io.openmesh.gateway.web.TraceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Outermost filter (runs before Spring Security) so every request, including ones rejected
 * with 401/403/429, gets:
 * <ul>
 *   <li>a W3C trace context: an incoming {@code traceparent} is continued, otherwise a new trace starts;</li>
 *   <li>{@code X-Trace-Id} and {@code X-Request-Id} response headers for support and log correlation;</li>
 *   <li>per-tenant metrics and usage metering for gateway traffic.</li>
 * </ul>
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestTelemetryWebFilter implements WebFilter {

    private static final Pattern TRACEPARENT = Pattern.compile("^00-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})$");
    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("^[A-Za-z0-9._:-]{1,64}$");
    private static final String INVALID_TRACE = "00000000000000000000000000000000";

    /** Paths served by the gateway itself; not metered as tenant traffic. */
    private static final List<String> INTERNAL_PREFIXES =
            List.of("/actuator", "/admin", "/auth", "/console", "/fallback", "/assets");

    private final TenantMeteringService metering;
    private final SecureRandom random = new SecureRandom();

    public RequestTelemetryWebFilter(TenantMeteringService metering) {
        this.metering = metering;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        long start = System.nanoTime();
        ServerHttpRequest request = exchange.getRequest();

        String traceId = null;
        String flags = "01";
        String incoming = request.getHeaders().getFirst(TraceContext.TRACEPARENT_HEADER);
        if (incoming != null) {
            var matcher = TRACEPARENT.matcher(incoming.trim().toLowerCase());
            if (matcher.matches() && !INVALID_TRACE.equals(matcher.group(1))) {
                traceId = matcher.group(1);
                flags = matcher.group(3);
            }
        }
        if (traceId == null) {
            traceId = randomHex(16);
        }
        String requestId = request.getHeaders().getFirst(TraceContext.REQUEST_ID_HEADER);
        if (requestId == null || !SAFE_REQUEST_ID.matcher(requestId).matches()) {
            requestId = randomHex(8);
        }

        String traceparent = "00-" + traceId + "-" + randomHex(8) + "-" + flags;
        String finalRequestId = requestId;
        ServerHttpRequest mutated = request.mutate().headers(h -> {
            h.set(TraceContext.TRACEPARENT_HEADER, traceparent);
            h.set(TraceContext.REQUEST_ID_HEADER, finalRequestId);
        }).build();

        ServerWebExchange traced = exchange.mutate().request(mutated).build();
        traced.getAttributes().put(TraceContext.TRACE_ID_ATTR, traceId);
        traced.getResponse().getHeaders().set(TraceContext.TRACE_ID_HEADER, traceId);
        traced.getResponse().getHeaders().set(TraceContext.REQUEST_ID_HEADER, requestId);

        String path = request.getPath().value();
        boolean gatewayTraffic = !isInternal(path);
        String finalTraceId = traceId;

        return chain.filter(traced).doFinally(signal -> {
            if (!gatewayTraffic) {
                return;
            }
            Duration duration = Duration.ofNanos(System.nanoTime() - start);
            HttpStatusCode code = traced.getResponse().getStatusCode();
            int status = code != null ? code.value() : (signal == SignalType.ON_ERROR ? 500 : 200);
            if (signal == SignalType.CANCEL && code == null) {
                status = 499; // client closed the connection
            }

            TenantContext context = traced.getAttribute(ZeroTrustIdentityFilter.TENANT_CONTEXT_ATTR);
            String tenant = context != null ? context.getTenantId() : TenantContext.ANONYMOUS;
            Route route = traced.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
            String routeId = route != null ? route.getId() : "unmatched";
            String method = request.getMethod().name();

            metering.recordRequest(tenant, routeId, method, status, duration);
            log.debug("trace={} tenant={} route={} {} {} -> {} in {}ms",
                    finalTraceId, tenant, routeId, method, path, status, duration.toMillis());
        });
    }

    private static boolean isInternal(String path) {
        if ("/".equals(path) || "/index.html".equals(path) || "/favicon.svg".equals(path)) {
            return true;
        }
        for (String prefix : INTERNAL_PREFIXES) {
            if (path.equals(prefix) || path.startsWith(prefix + "/")) {
                return true;
            }
        }
        return false;
    }

    private String randomHex(int bytes) {
        byte[] buffer = new byte[bytes];
        random.nextBytes(buffer);
        return HexFormat.of().formatHex(buffer);
    }
}
