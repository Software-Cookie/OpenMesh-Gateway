package io.openmesh.gateway.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * End-to-end tests against a real Redis 7 (Testcontainers) and a mock downstream service
 * that echoes back the headers the gateway forwarded. Skipped when Docker is unavailable.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient(timeout = "20s")
@ActiveProfiles("dev")
@Testcontainers(disabledWithoutDocker = true)
class GatewayIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> ECHOED = List.of("X-User-Id", "X-Tenant-Id", "X-User-Roles", "X-Auth-Method",
            "X-Gateway-Signature", "X-API-Key", "traceparent");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    static final MockWebServer DOWNSTREAM = new MockWebServer();

    static {
        DOWNSTREAM.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                if (request.getPath() != null && request.getPath().contains("/fail")) {
                    return new MockResponse().setResponseCode(500);
                }
                Map<String, Object> echo = new LinkedHashMap<>();
                echo.put("path", request.getPath());
                ECHOED.forEach(h -> echo.put(h, request.getHeader(h)));
                try {
                    return new MockResponse().setHeader("Content-Type", "application/json").setBody(JSON.writeValueAsString(echo));
                } catch (IOException e) {
                    return new MockResponse().setResponseCode(500);
                }
            }
        });
        try {
            DOWNSTREAM.start();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        String downstream = "http://localhost:" + DOWNSTREAM.getPort();
        registry.add("DOWNSTREAM_ALPHA_URL", () -> downstream);
        registry.add("DOWNSTREAM_BETA_URL", () -> downstream);
        registry.add("openmesh.metering.flush-interval", () -> "PT1S");
    }

    @AfterAll
    static void stopDownstream() throws IOException {
        DOWNSTREAM.shutdown();
    }

    @Autowired
    private WebTestClient client;

    private String token(String tenant, String role, String tier, String... scopes) {
        Map<String, Object> body = Map.of("tenantId", tenant, "userId", "user-" + tenant, "roles", List.of(role),
                "scopes", List.of(scopes), "tier", tier);
        JsonNode response = client.post().uri("/auth/token").contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange().expectStatus().isOk().expectBody(JsonNode.class).returnResult().getResponseBody();
        return response.get("token").asText();
    }

    private String adminToken() {
        return token("tenant-admin", "ROLE_ADMIN", "ENTERPRISE", "read", "write");
    }

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    @DisplayName("Health, console UI and console config are public")
    void publicEndpoints() {
        client.get().uri("/actuator/health").exchange().expectStatus().isOk().expectBody().jsonPath("$.status").isEqualTo("UP");
        client.get().uri("/").exchange().expectStatus().isOk()
                .expectBody(String.class).value(html -> assertThat(html).contains("OpenMesh Gateway Console"));
        client.get().uri("/console/config").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.devIssuerEnabled").isEqualTo(true);
    }

    @Test
    @DisplayName("Admin API: 401 without a token, 403 without ROLE_ADMIN")
    void adminApiIsProtected() {
        client.get().uri("/admin/v1/routes").exchange().expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.reason").isEqualTo("UNAUTHENTICATED");
        client.get().uri("/admin/v1/routes").headers(h -> h.setBearerAuth(token("t-user", "ROLE_USER", "PRO", "read")))
                .exchange().expectStatus().isForbidden();
        client.get().uri("/actuator/metrics").exchange().expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("Proxied request carries verified, signed identity; spoofed headers are replaced")
    void verifiedIdentityReachesDownstream() {
        client.get().uri("/service-alpha/orders")
                .headers(h -> {
                    h.setBearerAuth(token("tenant-acme", "ROLE_USER", "PRO", "read"));
                    h.set("X-User-Id", "attacker");
                    h.set("X-Tenant-Id", "victim");
                })
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists("X-Trace-Id")
                .expectHeader().valueEquals("X-RateLimit-Tier", "PRO")
                .expectBody()
                .jsonPath("$.path").isEqualTo("/orders")
                .jsonPath("$['X-User-Id']").isEqualTo("user-tenant-acme")
                .jsonPath("$['X-Tenant-Id']").isEqualTo("tenant-acme")
                .jsonPath("$['X-Auth-Method']").isEqualTo("JWT")
                .jsonPath("$['X-Gateway-Signature']").value(sig -> assertThat((String) sig).startsWith("v1="))
                .jsonPath("$.traceparent").exists();
    }

    @Test
    @DisplayName("Route scopes are enforced (403 INSUFFICIENT_SCOPE) and bad tokens are rejected (401)")
    void scopesAndTokens() {
        client.get().uri("/service-alpha/orders").headers(h -> h.setBearerAuth(token("t-noscope", "ROLE_USER", "PRO", "write")))
                .exchange().expectStatus().isForbidden()
                .expectBody().jsonPath("$.reason").isEqualTo("INSUFFICIENT_SCOPE");
        client.get().uri("/service-alpha/orders").headers(h -> h.setBearerAuth("not.a.jwt"))
                .exchange().expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("Token bucket in Redis limits a FREE tenant and anonymous callers")
    void rateLimitingWithRealRedis() {
        String free = token(unique("free"), "ROLE_USER", "FREE", "read");
        int limited = 0;
        String retryAfter = null;
        for (int i = 0; i < 30; i++) {
            var result = client.get().uri("/service-beta/items").headers(h -> h.setBearerAuth(free))
                    .exchange().returnResult(String.class);
            if (result.getStatus() == HttpStatus.TOO_MANY_REQUESTS) {
                limited++;
                retryAfter = result.getResponseHeaders().getFirst("Retry-After");
            }
        }
        assertThat(limited).as("FREE tier allows a burst of 10").isGreaterThanOrEqualTo(5);
        assertThat(retryAfter).isNotBlank();

        int anonymousLimited = 0;
        for (int i = 0; i < 12; i++) {
            if (client.get().uri("/public/status").exchange().returnResult(String.class).getStatus() == HttpStatus.TOO_MANY_REQUESTS) {
                anonymousLimited++;
            }
        }
        assertThat(anonymousLimited).as("public route allows a burst of 5 per IP").isGreaterThanOrEqualTo(3);
    }

    @Test
    @DisplayName("Upstream 500 goes through the circuit breaker fallback as 502")
    void circuitBreakerFallback() {
        client.get().uri("/service-beta/fail").headers(h -> h.setBearerAuth(adminToken()))
                .exchange().expectStatus().isEqualTo(HttpStatus.BAD_GATEWAY)
                .expectBody().jsonPath("$.reason").isEqualTo("UPSTREAM_ERROR")
                .jsonPath("$.path").isEqualTo("/service-beta/fail");
    }

    @Test
    @DisplayName("Dynamic route lifecycle: validate, create, route traffic, update, delete")
    void dynamicRouteLifecycle() {
        String admin = adminToken();
        String id = unique("it-route");
        String downstream = "http://localhost:" + DOWNSTREAM.getPort();

        client.post().uri("/admin/v1/routes").headers(h -> h.setBearerAuth(admin)).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("id", id, "uri", "http://169.254.169.254", "predicates", List.of("Nope=/x")))
                .exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.details.length()").isEqualTo(2);

        client.post().uri("/admin/v1/routes").headers(h -> h.setBearerAuth(admin)).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("id", id, "uri", downstream, "predicates", List.of("Path=/" + id + "/**"),
                        "filters", List.of("StripPrefix=1")))
                .exchange().expectStatus().isCreated()
                .expectBody().jsonPath("$.source").isEqualTo("dynamic");

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                client.get().uri("/" + id + "/inventory").headers(h -> h.setBearerAuth(admin))
                        .exchange().expectStatus().isOk().expectBody().jsonPath("$.path").isEqualTo("/inventory"));

        client.put().uri("/admin/v1/routes/" + id).headers(h -> h.setBearerAuth(admin)).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("uri", downstream, "predicates", List.of("Path=/" + id + "/**"),
                        "filters", List.of("StripPrefix=1"), "metadata", Map.of("requiredScopes", "special")))
                .exchange().expectStatus().isOk();

        String reader = token(unique("reader"), "ROLE_USER", "PRO", "read");
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                client.get().uri("/" + id + "/inventory").headers(h -> h.setBearerAuth(reader))
                        .exchange().expectStatus().isForbidden());

        client.delete().uri("/admin/v1/routes/alpha-service-route").headers(h -> h.setBearerAuth(admin))
                .exchange().expectStatus().isEqualTo(HttpStatus.CONFLICT);
        client.delete().uri("/admin/v1/routes/" + id).headers(h -> h.setBearerAuth(admin))
                .exchange().expectStatus().isNoContent();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                client.get().uri("/" + id + "/inventory").headers(h -> h.setBearerAuth(admin))
                        .exchange().expectStatus().isNotFound());
    }

    @Test
    @DisplayName("API key lifecycle: create, authenticate (never forwarded), revoke; usage and audit are recorded")
    void apiKeysUsageAndAudit() {
        String admin = adminToken();
        String tenant = unique("billing");
        JsonNode created = client.post().uri("/admin/v1/api-keys").headers(h -> h.setBearerAuth(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "batch", "tenantId", tenant, "tier", "ENTERPRISE", "scopes", List.of("read")))
                .exchange().expectStatus().isCreated().expectBody(JsonNode.class).returnResult().getResponseBody();
        String apiKey = created.get("apiKey").asText();
        String keyId = created.get("record").get("id").asText();
        assertThat(apiKey).startsWith("omk_");

        client.get().uri("/service-alpha/orders").header("X-API-Key", apiKey)
                .exchange().expectStatus().isOk()
                .expectHeader().valueEquals("X-RateLimit-Tier", "ENTERPRISE")
                .expectBody()
                .jsonPath("$['X-Tenant-Id']").isEqualTo(tenant)
                .jsonPath("$['X-Auth-Method']").isEqualTo("API_KEY")
                .jsonPath("$['X-API-Key']").doesNotExist();

        client.get().uri("/admin/v1/routes").header("X-API-Key", apiKey).exchange().expectStatus().isForbidden();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                client.get().uri("/admin/v1/usage").headers(h -> h.setBearerAuth(admin)).exchange()
                        .expectStatus().isOk()
                        .expectBody().jsonPath("$.tenants[?(@.tenantId == '" + tenant + "')].requests").isEqualTo(1));

        client.delete().uri("/admin/v1/api-keys/" + keyId).headers(h -> h.setBearerAuth(admin))
                .exchange().expectStatus().isNoContent();
        client.get().uri("/service-alpha/orders").header("X-API-Key", apiKey).exchange().expectStatus().isUnauthorized();

        client.get().uri("/admin/v1/audit?limit=20").headers(h -> h.setBearerAuth(admin)).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[?(@.target == '" + keyId + "')].action").value(actions ->
                        assertThat(actions.toString()).contains("API_KEY_CREATED", "API_KEY_REVOKED"));
    }
}
