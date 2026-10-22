package io.openmesh.gateway.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RoutePolicyTest {

    @Test
    @DisplayName("Reads required scopes from a string, a list, or the index map YAML binding produces")
    void readsScopesInEveryShape() {
        assertThat(RoutePolicy.from(Map.of("requiredScopes", "read write")).requiredScopes())
                .containsExactly("read", "write");
        assertThat(RoutePolicy.from(Map.of("requiredScopes", "read,write")).requiredScopes())
                .containsExactly("read", "write");
        assertThat(RoutePolicy.from(Map.of("requiredScopes", List.of("orders:read"))).requiredScopes())
                .containsExactly("orders:read");

        Map<String, Object> yamlList = new LinkedHashMap<>();
        yamlList.put("0", "a");
        yamlList.put("1", "b");
        assertThat(RoutePolicy.from(Map.of("requiredScopes", yamlList)).requiredScopes()).containsExactly("a", "b");
    }

    @Test
    @DisplayName("Parses rate limit overrides from numbers or strings")
    void parsesRateLimit() {
        RoutePolicy policy = RoutePolicy.from(Map.of("rateLimit", Map.of("replenishRate", "2", "burstCapacity", 5)));
        assertThat(policy.rateLimitEnabled()).isTrue();
        assertThat(policy.anonymousReplenishRate()).isEqualTo(2.0);
        assertThat(policy.anonymousBurstCapacity()).isEqualTo(5.0);

        assertThat(RoutePolicy.from(Map.of("rateLimit", Map.of("enabled", "false"))).rateLimitEnabled()).isFalse();
    }

    @Test
    @DisplayName("Empty metadata means no scopes and rate limiting on with defaults")
    void defaults() {
        RoutePolicy policy = RoutePolicy.from(null);
        assertThat(policy.requiredScopes()).isEmpty();
        assertThat(policy.rateLimitEnabled()).isTrue();
        assertThat(policy.anonymousReplenishRate()).isNull();
    }
}
