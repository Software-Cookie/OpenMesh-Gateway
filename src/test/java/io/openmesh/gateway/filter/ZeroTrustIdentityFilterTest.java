package io.openmesh.gateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.openmesh.gateway.config.OpenMeshProperties;
import io.openmesh.gateway.model.ApiKeyRecord;
import io.openmesh.gateway.model.TenantContext;
import io.openmesh.gateway.security.ApiKeyAuthenticationToken;
import io.openmesh.gateway.security.ClientIpResolver;
import io.openmesh.gateway.security.IdentityHeaderSigner;
import io.openmesh.gateway.web.ErrorResponseWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ZeroTrustIdentityFilterTest {

    private ZeroTrustIdentityFilter filter;
    private final AtomicReference<ServerHttpRequest> forwarded = new AtomicReference<>();
    private final GatewayFilterChain chain = exchange -> {
        forwarded.set(exchange.getRequest());
        return Mono.empty();
    };

    @BeforeEach
    void setUp() {
        OpenMeshProperties properties = new OpenMeshProperties();
        properties.getSecurity().setIdentitySigningSecret("test-secret");
        filter = new ZeroTrustIdentityFilter(properties, new ClientIpResolver(properties),
                new IdentityHeaderSigner(properties, new MockEnvironment()), new ErrorResponseWriter(new ObjectMapper()));
    }

    private static MockServerHttpRequest.BaseBuilder<?> spoofed(String path) {
        return MockServerHttpRequest.get(path)
                .remoteAddress(new InetSocketAddress("10.1.2.3", 5555))
                .header(ZeroTrustIdentityFilter.HEADER_USER_ID, "attacker")
                .header(ZeroTrustIdentityFilter.HEADER_TENANT_ID, "victim")
                .header(ZeroTrustIdentityFilter.HEADER_GATEWAY_SIGNATURE, "v1=forged")
                .header("Tenant-ID", "victim")
                .header("X-API-Key", "omk_secret");
    }

    private static Jwt jwt(String subject, String tenant, String tier, String scope) {
        Instant now = Instant.now();
        return Jwt.withTokenValue("token").header("alg", "RS256").subject(subject)
                .claim("tenant_id", tenant).claim("tier", tier).claim("scope", scope)
                .issuedAt(now).expiresAt(now.plusSeconds(300)).build();
    }

    private Mono<Void> run(MockServerWebExchange exchange, Authentication auth) {
        Mono<Void> result = filter.filter(exchange, chain);
        return auth == null ? result
                : result.contextWrite(ReactiveSecurityContextHolder.withSecurityContext(Mono.just(new SecurityContextImpl(auth))));
    }

    private static void attachRoute(MockServerWebExchange exchange, Object requiredScopes) {
        Route.AsyncBuilder builder = Route.async().id("orders").uri(URI.create("http://orders")).predicate(e -> true);
        if (requiredScopes != null) {
            builder.metadata("requiredScopes", requiredScopes);
        }
        exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR, builder.build());
    }

    @Test
    @DisplayName("Anonymous caller: spoofed identity and Tenant-ID headers are ignored, API key never forwarded")
    void anonymousCannotSpoof() {
        MockServerWebExchange exchange = MockServerWebExchange.from(spoofed("/public/x"));

        StepVerifier.create(run(exchange, null)).verifyComplete();

        ServerHttpRequest req = forwarded.get();
        assertThat(req.getHeaders().getFirst(ZeroTrustIdentityFilter.HEADER_USER_ID)).isEqualTo("anonymous");
        assertThat(req.getHeaders().getFirst(ZeroTrustIdentityFilter.HEADER_TENANT_ID)).isEqualTo("anonymous");
        assertThat(req.getHeaders().getFirst(ZeroTrustIdentityFilter.HEADER_GATEWAY_SIGNATURE)).isNotEqualTo("v1=forged").startsWith("v1=");
        assertThat(req.getHeaders().containsKey("X-API-Key")).isFalse();
        TenantContext context = exchange.getAttribute(ZeroTrustIdentityFilter.TENANT_CONTEXT_ATTR);
        assertThat(context.getClientIp()).isEqualTo("10.1.2.3");
        assertThat(context.isAuthenticated()).isFalse();
    }

    @Test
    @DisplayName("JWT caller: verified claims are forwarded and override spoofed headers")
    void jwtIdentityPropagated() {
        MockServerWebExchange exchange = MockServerWebExchange.from(spoofed("/orders/1"));
        JwtAuthenticationToken auth = new JwtAuthenticationToken(jwt("alice", "acme", "ENTERPRISE", "read write"),
                List.of(new SimpleGrantedAuthority("ROLE_USER"), new SimpleGrantedAuthority("SCOPE_read")), "alice");
        attachRoute(exchange, "read");

        StepVerifier.create(run(exchange, auth)).verifyComplete();

        ServerHttpRequest req = forwarded.get();
        assertThat(req.getHeaders().getFirst(ZeroTrustIdentityFilter.HEADER_USER_ID)).isEqualTo("alice");
        assertThat(req.getHeaders().getFirst(ZeroTrustIdentityFilter.HEADER_TENANT_ID)).isEqualTo("acme");
        assertThat(req.getHeaders().getFirst(ZeroTrustIdentityFilter.HEADER_USER_ROLES)).isEqualTo("ROLE_USER");
        assertThat(req.getHeaders().getFirst(ZeroTrustIdentityFilter.HEADER_AUTH_METHOD)).isEqualTo("JWT");
        TenantContext context = exchange.getAttribute(ZeroTrustIdentityFilter.TENANT_CONTEXT_ATTR);
        assertThat(context.getTier()).isEqualTo("ENTERPRISE");
    }

    @Test
    @DisplayName("Missing required scope returns 403 and never reaches the downstream service")
    void missingScopeForbidden() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/orders"));
        attachRoute(exchange, List.of("orders:write"));
        JwtAuthenticationToken auth = new JwtAuthenticationToken(jwt("bob", "acme", "PRO", "read"),
                List.of(new SimpleGrantedAuthority("ROLE_USER")), "bob");

        StepVerifier.create(run(exchange, auth)).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(forwarded.get()).isNull();
    }

    @Test
    @DisplayName("Admins bypass route scope requirements")
    void adminBypassesScopes() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/orders"));
        attachRoute(exchange, "orders:write");
        JwtAuthenticationToken auth = new JwtAuthenticationToken(jwt("root", "acme", "PRO", ""),
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")), "root");

        StepVerifier.create(run(exchange, auth)).verifyComplete();

        assertThat(forwarded.get()).isNotNull();
    }

    @Test
    @DisplayName("Anonymous caller on a route with required scopes gets 401")
    void anonymousOnScopedRoute() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/orders"));
        attachRoute(exchange, "read");

        StepVerifier.create(run(exchange, null)).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("API key caller: tenant, tier and scopes come from the stored key record")
    void apiKeyIdentity() {
        MockServerWebExchange exchange = MockServerWebExchange.from(spoofed("/orders"));
        ApiKeyRecord record = ApiKeyRecord.builder().id("key_1").tenantId("billing").tier("ENTERPRISE")
                .scopes(List.of("read")).build();
        attachRoute(exchange, "read");

        StepVerifier.create(run(exchange, ApiKeyAuthenticationToken.authenticated(record))).verifyComplete();

        ServerHttpRequest req = forwarded.get();
        assertThat(req.getHeaders().getFirst(ZeroTrustIdentityFilter.HEADER_TENANT_ID)).isEqualTo("billing");
        assertThat(req.getHeaders().getFirst(ZeroTrustIdentityFilter.HEADER_AUTH_METHOD)).isEqualTo("API_KEY");
        assertThat(req.getHeaders().containsKey("X-API-Key")).isFalse();
    }
}
