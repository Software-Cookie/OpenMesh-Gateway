package io.openmesh.gateway.controller;

import io.openmesh.gateway.config.OpenMeshProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.Map;

/**
 * Public, non-sensitive data the admin console needs before the operator signs in.
 * Actuator runs on the private management port, so the console reads health from here.
 */
@RestController
public class ConsoleController {

    private static final Duration HEALTH_CACHE = Duration.ofSeconds(5);

    private final OpenMeshProperties properties;
    private final String version;
    private final Mono<Map<String, String>> health;

    public ConsoleController(OpenMeshProperties properties, ObjectProvider<BuildProperties> buildProperties,
                             ObjectProvider<HealthEndpoint> healthEndpoint) {
        this.properties = properties;
        BuildProperties build = buildProperties.getIfAvailable();
        this.version = build != null ? build.getVersion() : "dev";
        // Overall status only (no component details). Cached so unauthenticated polling cannot hammer Redis.
        this.health = Mono.fromCallable(() -> {
                    HealthEndpoint endpoint = healthEndpoint.getIfAvailable();
                    return endpoint == null ? "UNKNOWN" : endpoint.health().getStatus().getCode();
                })
                .subscribeOn(Schedulers.boundedElastic())
                .timeout(Duration.ofSeconds(3))
                .onErrorReturn("DOWN")
                .map(status -> Map.of("status", status))
                .cache(HEALTH_CACHE);
    }

    @GetMapping("/console/config")
    public Map<String, Object> config() {
        return Map.of(
                "devIssuerEnabled", properties.getSecurity().getDevIssuer().isEnabled(),
                "apiKeyHeader", properties.getSecurity().getApiKeyHeader(),
                "version", version);
    }

    @GetMapping("/console/health")
    public Mono<Map<String, String>> health() {
        return health;
    }
}
