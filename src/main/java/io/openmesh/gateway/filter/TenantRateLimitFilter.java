package io.openmesh.gateway.filter;

import io.openmesh.gateway.config.OpenMeshProperties;
import io.openmesh.gateway.model.RateLimitResult;
import io.openmesh.gateway.model.RateLimitTier;
import io.openmesh.gateway.model.RoutePolicy;
import io.openmesh.gateway.model.TenantContext;
import io.openmesh.gateway.service.RateLimitService;
import io.openmesh.gateway.service.TenantMeteringService;
import io.openmesh.gateway.web.ErrorResponseWriter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Applies a distributed token-bucket limit to every routed request (static and dynamic routes).
 * <ul>
 *   <li>Authenticated callers share one bucket per tenant, sized by their plan tier.</li>
 *   <li>Anonymous callers get one bucket per route and client IP, sized by the route's
 *       {@code rateLimit} metadata or the global anonymous default.</li>
 *   <li>Routes can opt out with {@code metadata.rateLimit.enabled: false}.</li>
 * </ul>
 * Runs after {@link ZeroTrustIdentityFilter} so the caller's identity is already verified.
 */
@Component
public class TenantRateLimitFilter implements GlobalFilter, Ordered {

    public static final int ORDER = ZeroTrustIdentityFilter.ORDER + 50;

    public static final String HEADER_LIMIT = "X-RateLimit-Limit";
    public static final String HEADER_REMAINING = "X-RateLimit-Remaining";
    public static final String HEADER_RESET = "X-RateLimit-Reset";
    public static final String HEADER_REPLENISH = "X-RateLimit-Replenish-Rate";
    public static final String HEADER_TIER = "X-RateLimit-Tier";

    private final RateLimitService rateLimitService;
    private final TenantMeteringService metering;
    private final ErrorResponseWriter errorWriter;
    private final OpenMeshProperties.RateLimit properties;

    public TenantRateLimitFilter(RateLimitService rateLimitService, TenantMeteringService metering,
                                 ErrorResponseWriter errorWriter, OpenMeshProperties properties) {
        this.rateLimitService = rateLimitService;
        this.metering = metering;
        this.errorWriter = errorWriter;
        this.properties = properties.getRateLimit();
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        TenantContext context = exchange.getAttribute(ZeroTrustIdentityFilter.TENANT_CONTEXT_ATTR);
        if (!properties.isEnabled() || route == null || context == null) {
            return chain.filter(exchange);
        }
        RoutePolicy policy = RoutePolicy.from(route.getMetadata());
        if (!policy.rateLimitEnabled()) {
            return chain.filter(exchange);
        }

        Limit limit = resolveLimit(context, policy, route.getId());
        return rateLimitService.checkRateLimit(limit.bucketKey(), limit.replenishRate(), limit.burstCapacity(), 1)
                .flatMap(result -> {
                    HttpHeaders headers = exchange.getResponse().getHeaders();
                    headers.set(HEADER_LIMIT, String.valueOf((long) result.getBurstCapacity()));
                    headers.set(HEADER_REPLENISH, String.valueOf((long) result.getReplenishRate()));
                    headers.set(HEADER_REMAINING, String.valueOf(result.getRemainingTokens()));
                    headers.set(HEADER_RESET, String.valueOf(result.getWaitOrResetSeconds()));
                    headers.set(HEADER_TIER, limit.label());

                    if (result.isAllowed()) {
                        return chain.filter(exchange);
                    }
                    return reject(exchange, context, route.getId(), result);
                });
    }

    private Mono<Void> reject(ServerWebExchange exchange, TenantContext context, String routeId, RateLimitResult result) {
        if (result.isDegraded()) {
            return errorWriter.write(exchange, HttpStatus.SERVICE_UNAVAILABLE, "RATE_LIMITER_UNAVAILABLE",
                    "Rate limiter is unavailable and the gateway is configured to fail closed");
        }
        metering.recordRateLimitRejection(context.getTenantId(), routeId);
        exchange.getResponse().getHeaders().set(HttpHeaders.RETRY_AFTER, String.valueOf(result.getWaitOrResetSeconds()));
        return errorWriter.write(exchange, HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                String.format("Rate limit exceeded: %d requests burst, %d requests/second sustained",
                        (long) result.getBurstCapacity(), (long) result.getReplenishRate()),
                b -> b.retryAfterSeconds((int) result.getWaitOrResetSeconds()));
    }

    private Limit resolveLimit(TenantContext context, RoutePolicy policy, String routeId) {
        if (context.isAuthenticated()) {
            RateLimitTier tier = RateLimitTier.fromString(context.getTier());
            return new Limit("tenant:" + context.getTenantId(), tier.getReplenishRate(), tier.getBurstCapacity(), tier.name());
        }
        double rate = policy.anonymousReplenishRate() != null ? policy.anonymousReplenishRate() : properties.getAnonymousReplenishRate();
        double burst = policy.anonymousBurstCapacity() != null ? policy.anonymousBurstCapacity() : properties.getAnonymousBurstCapacity();
        return new Limit("anon:" + routeId + ":" + context.getClientIp(), rate, burst, "ANONYMOUS");
    }

    private record Limit(String bucketKey, double replenishRate, double burstCapacity, String label) {
    }
}
