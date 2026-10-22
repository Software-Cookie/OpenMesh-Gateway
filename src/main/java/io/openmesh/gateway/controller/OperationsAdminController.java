package io.openmesh.gateway.controller;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.openmesh.gateway.model.AuditEvent;
import io.openmesh.gateway.model.TenantUsage;
import io.openmesh.gateway.service.ApiKeyService;
import io.openmesh.gateway.service.AuditService;
import io.openmesh.gateway.service.DynamicRouteService;
import io.openmesh.gateway.service.TenantMeteringService;
import io.openmesh.gateway.web.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Dashboard data for operators: overview, circuit breakers, tenant usage and the audit log. */
@RestController
@RequestMapping("/admin/v1")
@RequiredArgsConstructor
public class OperationsAdminController {

    private final DynamicRouteService routeService;
    private final ApiKeyService apiKeyService;
    private final TenantMeteringService metering;
    private final AuditService audit;
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final ReactiveStringRedisTemplate redis;

    public record CircuitBreakerView(String name, String state, float failureRate, float slowCallRate,
                                     int bufferedCalls, int failedCalls, long notPermittedCalls) {
    }

    @GetMapping("/overview")
    public Mono<Map<String, Object>> overview() {
        Mono<String> redisStatus = redis.execute(connection -> connection.ping()).next()
                .timeout(Duration.ofSeconds(1))
                .map(pong -> "UP")
                .onErrorReturn("DOWN");
        Mono<Long> apiKeys = apiKeyService.count().onErrorReturn(-1L);
        Mono<List<TenantUsage>> usage = metering.getUsage(LocalDate.parse(TenantMeteringService.today()));

        return Mono.zip(routeService.countRoutes(), apiKeys, usage, redisStatus).map(t -> {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("staticRoutes", t.getT1()[0]);
            result.put("dynamicRoutes", t.getT1()[1]);
            result.put("apiKeys", t.getT2());
            result.put("requestsToday", t.getT3().stream().mapToLong(TenantUsage::requests).sum());
            result.put("rejectedToday", t.getT3().stream().mapToLong(TenantUsage::rejected).sum());
            result.put("activeTenantsToday", t.getT3().size());
            result.put("redis", t.getT4());
            result.put("circuitBreakers", circuitBreakers());
            return result;
        });
    }

    /** Cumulative traffic counters for live charts; the console derives rates from consecutive snapshots. */
    @GetMapping("/metrics/live")
    public TenantMeteringService.LiveSnapshot liveMetrics() {
        return metering.liveSnapshot();
    }

    @GetMapping("/circuit-breakers")
    public List<CircuitBreakerView> circuitBreakers() {
        return circuitBreakerRegistry.getAllCircuitBreakers().stream()
                .sorted(Comparator.comparing(CircuitBreaker::getName))
                .map(cb -> {
                    CircuitBreaker.Metrics m = cb.getMetrics();
                    return new CircuitBreakerView(cb.getName(), cb.getState().name(),
                            m.getFailureRate(), m.getSlowCallRate(), m.getNumberOfBufferedCalls(),
                            m.getNumberOfFailedCalls(), m.getNumberOfNotPermittedCalls());
                })
                .toList();
    }

    /** Billable usage per tenant for a UTC day (default: today). */
    @GetMapping("/usage")
    public Mono<Map<String, Object>> usage(@RequestParam(required = false) String date) {
        LocalDate day;
        try {
            day = date == null || date.isBlank() ? LocalDate.parse(TenantMeteringService.today()) : LocalDate.parse(date);
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("date must be formatted as YYYY-MM-DD", null);
        }
        return metering.getUsage(day).map(tenants -> Map.of("date", day.toString(), "tenants", tenants));
    }

    @GetMapping("/audit")
    public Flux<AuditEvent> auditLog(@RequestParam(defaultValue = "50") int limit) {
        return audit.recent(limit)
                .onErrorMap(e -> ApiException.unavailable("Audit store (Redis) is unavailable"));
    }
}
