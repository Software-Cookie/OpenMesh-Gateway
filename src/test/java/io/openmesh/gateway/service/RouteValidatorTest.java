package io.openmesh.gateway.service;

import io.openmesh.gateway.config.OpenMeshProperties;
import io.openmesh.gateway.model.RouteDefinitionDto;
import io.openmesh.gateway.web.ApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.factory.AddRequestHeaderGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.StripPrefixGatewayFilterFactory;
import org.springframework.cloud.gateway.handler.predicate.MethodRoutePredicateFactory;
import org.springframework.cloud.gateway.handler.predicate.PathRoutePredicateFactory;
import org.springframework.cloud.gateway.route.RouteDefinition;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RouteValidatorTest {

    private RouteValidator validator(OpenMeshProperties properties) {
        return new RouteValidator(
                List.of(new PathRoutePredicateFactory(), new MethodRoutePredicateFactory()),
                List.of(new StripPrefixGatewayFilterFactory(), new AddRequestHeaderGatewayFilterFactory()),
                properties);
    }

    private RouteDefinitionDto route(String uri, List<String> predicates, List<String> filters) {
        return RouteDefinitionDto.builder().id("orders").uri(uri).predicates(predicates).filters(filters).build();
    }

    @Test
    @DisplayName("Accepts a well-formed route and converts it")
    void acceptsValidRoute() {
        RouteDefinitionDto dto = route("http://orders:8080", List.of("Path=/orders/**"), List.of("StripPrefix=1"));
        dto.setMetadata(Map.of("requiredScopes", "orders:read", "rateLimit", Map.of("replenishRate", 10)));

        RouteDefinition definition = validator(new OpenMeshProperties()).validate(dto);

        assertThat(definition.getId()).isEqualTo("orders");
        assertThat(definition.getPredicates()).hasSize(1);
        assertThat(definition.getFilters().get(0).getName()).isEqualTo("StripPrefix");
    }

    @Test
    @DisplayName("Reports every problem at once: unknown names, blocked host, identity header tampering")
    void reportsAllProblems() {
        RouteDefinitionDto dto = route("http://169.254.169.254/latest", List.of("Bogus=/x"),
                List.of("NoSuchFilter=1", "AddRequestHeader=X-Tenant-Id,victim"));

        assertThatThrownBy(() -> validator(new OpenMeshProperties()).validate(dto))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getDetails()).containsExactlyInAnyOrder(
                        "uri host '169.254.169.254' is blocked",
                        "unknown predicate 'Bogus'",
                        "unknown filter 'NoSuchFilter'",
                        "filter 'AddRequestHeader' may not modify gateway identity headers"));
    }

    @Test
    @DisplayName("Rejects disallowed schemes and invalid ids")
    void rejectsSchemeAndId() {
        RouteValidator v = validator(new OpenMeshProperties());
        assertThatThrownBy(() -> v.validate(route("file:///etc/passwd", List.of("Path=/x/**"), List.of())))
                .isInstanceOf(ApiException.class);

        RouteDefinitionDto badId = route("http://a:1", List.of("Path=/x/**"), List.of());
        badId.setId("bad id!");
        assertThatThrownBy(() -> v.validate(badId)).isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("Enforces the allowed host list when configured")
    void enforcesAllowedHosts() {
        OpenMeshProperties properties = new OpenMeshProperties();
        properties.getRoutes().setAllowedHosts(List.of("*.svc.cluster.local"));
        RouteValidator v = validator(properties);

        v.validate(route("http://orders.shop.svc.cluster.local:8080", List.of("Path=/o/**"), List.of()));
        assertThatThrownBy(() -> v.validate(route("http://evil.example.com", List.of("Path=/o/**"), List.of())))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Invalid route definition");
    }
}
