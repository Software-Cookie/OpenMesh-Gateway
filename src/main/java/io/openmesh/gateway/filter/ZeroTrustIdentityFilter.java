package io.openmesh.gateway.filter;

import io.openmesh.gateway.config.OpenMeshProperties;
import io.openmesh.gateway.model.ApiKeyRecord;
import io.openmesh.gateway.model.RoutePolicy;
import io.openmesh.gateway.model.TenantContext;
import io.openmesh.gateway.model.TenantContext.AuthMethod;
import io.openmesh.gateway.security.ApiKeyAuthenticationToken;
import io.openmesh.gateway.security.ClientIpResolver;
import io.openmesh.gateway.security.IdentityHeaderSigner;
import io.openmesh.gateway.security.JwtClaims;
import io.openmesh.gateway.web.ErrorResponseWriter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;

/**
 * Zero-trust identity propagation for every routed request.
 * <ol>
 *   <li>Strips any identity headers the caller sent (anti-spoofing).</li>
 *   <li>Builds a {@link TenantContext} from the verified JWT or API key; unauthenticated
 *       callers on public routes become tenant "anonymous" keyed by client IP.</li>
 *   <li>Enforces the route's required scopes (403 when missing).</li>
 *   <li>Forwards the verified identity to the downstream service, HMAC-signed when a secret is set.</li>
 * </ol>
 */
@Slf4j
@Component
public class ZeroTrustIdentityFilter implements GlobalFilter, Ordered {

    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 50;
    public static final String TENANT_CONTEXT_ATTR = "openmesh.tenantContext";

    public static final String HEADER_USER_ID = "X-User-Id";
    public static final String HEADER_TENANT_ID = "X-Tenant-Id";
    public static final String HEADER_USER_ROLES = "X-User-Roles";
    public static final String HEADER_USER_SCOPES = "X-User-Scopes";
    public static final String HEADER_AUTH_METHOD = "X-Auth-Method";
    public static final String HEADER_GATEWAY_VERIFIED = "X-Gateway-Verified";
    public static final String HEADER_GATEWAY_TIMESTAMP = "X-Gateway-Timestamp";
    public static final String HEADER_GATEWAY_SIGNATURE = "X-Gateway-Signature";

    /** Headers only the gateway may set. Anything a client sends under these names is dropped. */
    public static final List<String> IDENTITY_HEADERS = List.of(
            HEADER_USER_ID, HEADER_TENANT_ID, HEADER_USER_ROLES, HEADER_USER_SCOPES, HEADER_AUTH_METHOD,
            HEADER_GATEWAY_VERIFIED, HEADER_GATEWAY_TIMESTAMP, HEADER_GATEWAY_SIGNATURE);

    private final OpenMeshProperties properties;
    private final ClientIpResolver clientIpResolver;
    private final IdentityHeaderSigner signer;
    private final ErrorResponseWriter errorWriter;

    public ZeroTrustIdentityFilter(OpenMeshProperties properties, ClientIpResolver clientIpResolver,
                                   IdentityHeaderSigner signer, ErrorResponseWriter errorWriter) {
        this.properties = properties;
        this.clientIpResolver = clientIpResolver;
        this.signer = signer;
        this.errorWriter = errorWriter;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .filter(Authentication::isAuthenticated)
                .map(auth -> buildContext(auth, exchange))
                .defaultIfEmpty(anonymousContext(exchange))
                .flatMap(context -> authorizeAndForward(exchange, chain, context));
    }

    private Mono<Void> authorizeAndForward(ServerWebExchange exchange, GatewayFilterChain chain, TenantContext context) {
        exchange.getAttributes().put(TENANT_CONTEXT_ATTR, context);

        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        List<String> requiredScopes = route != null ? RoutePolicy.from(route.getMetadata()).requiredScopes() : List.of();
        if (!requiredScopes.isEmpty()) {
            if (!context.isAuthenticated()) {
                return errorWriter.write(exchange, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                        "This route requires authentication");
            }
            List<String> missing = requiredScopes.stream().filter(s -> !context.getScopes().contains(s)).toList();
            if (!missing.isEmpty() && !context.isAdmin()) {
                log.warn("Scope check failed: user={} tenant={} route={} missing={}",
                        context.getUserId(), context.getTenantId(), route.getId(), missing);
                return errorWriter.write(exchange, HttpStatus.FORBIDDEN, "INSUFFICIENT_SCOPE",
                        "Missing required scope(s): " + String.join(", ", missing));
            }
        }

        return chain.filter(exchange.mutate().request(withIdentityHeaders(exchange.getRequest(), context)).build());
    }

    private ServerHttpRequest withIdentityHeaders(ServerHttpRequest request, TenantContext context) {
        String roles = String.join(",", context.getRoles());
        String scopes = String.join(" ", context.getScopes());
        String method = context.getAuthMethod().name();
        String timestamp = String.valueOf(Instant.now().toEpochMilli());
        String apiKeyHeader = properties.getSecurity().getApiKeyHeader();

        return request.mutate().headers(headers -> {
            IDENTITY_HEADERS.forEach(headers::remove);
            // API keys are secrets: never forward them to downstream services
            headers.remove(apiKeyHeader);
            headers.set(HEADER_USER_ID, context.getUserId());
            headers.set(HEADER_TENANT_ID, context.getTenantId());
            headers.set(HEADER_USER_ROLES, roles);
            headers.set(HEADER_USER_SCOPES, scopes);
            headers.set(HEADER_AUTH_METHOD, method);
            headers.set(HEADER_GATEWAY_VERIFIED, "true");
            headers.set(HEADER_GATEWAY_TIMESTAMP, timestamp);
            signer.sign(context.getUserId(), context.getTenantId(), roles, scopes, method, timestamp)
                    .ifPresent(signature -> headers.set(HEADER_GATEWAY_SIGNATURE, signature));
        }).build();
    }

    private TenantContext buildContext(Authentication auth, ServerWebExchange exchange) {
        String clientIp = clientIpResolver.resolve(exchange);
        List<String> roles = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("ROLE_"))
                .toList();

        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            OpenMeshProperties.Jwt claims = properties.getSecurity().getJwt();
            Jwt jwt = jwtAuth.getToken();
            String tenant = JwtClaims.getString(jwt, claims.getTenantClaim());
            return TenantContext.builder()
                    .tenantId(tenant != null ? tenant : "tenant-" + jwt.getSubject())
                    .userId(jwt.getSubject())
                    .roles(roles)
                    .scopes(JwtClaims.scopes(jwt))
                    .tier(JwtClaims.getString(jwt, claims.getTierClaim()))
                    .authMethod(AuthMethod.JWT)
                    .clientIp(clientIp)
                    .build();
        }
        if (auth instanceof ApiKeyAuthenticationToken apiKeyAuth) {
            ApiKeyRecord record = apiKeyAuth.getRecord();
            return TenantContext.builder()
                    .tenantId(record.getTenantId())
                    .userId("apikey:" + record.getId())
                    .roles(roles)
                    .scopes(record.getScopes())
                    .tier(record.getTier())
                    .authMethod(AuthMethod.API_KEY)
                    .clientIp(clientIp)
                    .build();
        }
        return anonymousContext(exchange);
    }

    private TenantContext anonymousContext(ServerWebExchange exchange) {
        return TenantContext.builder()
                .tenantId(TenantContext.ANONYMOUS)
                .userId(TenantContext.ANONYMOUS)
                .roles(List.of("ROLE_ANONYMOUS"))
                .authMethod(AuthMethod.ANONYMOUS)
                .clientIp(clientIpResolver.resolve(exchange))
                .build();
    }
}
