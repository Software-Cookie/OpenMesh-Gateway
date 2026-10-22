package io.openmesh.gateway.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.openmesh.gateway.config.OpenMeshProperties;
import io.openmesh.gateway.model.TenantUsage;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * Per-tenant metering.
 * <ul>
 *   <li>Prometheus metrics (throughput, latency histogram, rate-limit rejections).</li>
 *   <li>Billable daily usage: counted in memory and flushed to Redis in batches so the hot path
 *       never waits on Redis. Counts from every gateway instance add up in the same Redis hash.</li>
 * </ul>
 */
@Slf4j
@Service
public class TenantMeteringService {

    public static final String METRIC_REQUESTS = "openmesh.gateway.requests";
    public static final String METRIC_DURATION = "openmesh.gateway.request.duration";
    public static final String METRIC_REJECTED = "openmesh.gateway.ratelimit.rejected";

    static final String USAGE_KEY = "openmesh:usage:";

    private final MeterRegistry meterRegistry;
    private final ReactiveStringRedisTemplate redis;
    private final Duration retention;

    private final Map<String, LongAdder> pendingRequests = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> pendingRejected = new ConcurrentHashMap<>();

    public TenantMeteringService(MeterRegistry meterRegistry, ReactiveStringRedisTemplate redis,
                                 OpenMeshProperties properties) {
        this.meterRegistry = meterRegistry;
        this.redis = redis;
        this.retention = properties.getMetering().getRetention();
    }

    public void recordRequest(String tenantId, String routeId, String method, int statusCode, Duration duration) {
        String tenant = safe(tenantId, "anonymous");
        String route = safe(routeId, "unmatched");
        String statusCategory = (statusCode / 100) + "xx";

        Counter.builder(METRIC_REQUESTS)
                .description("Gateway requests per tenant, route and status")
                .tag("tenant", tenant)
                .tag("route", route)
                .tag("method", safe(method, "UNKNOWN"))
                .tag("status", String.valueOf(statusCode))
                .tag("status_category", statusCategory)
                .register(meterRegistry)
                .increment();

        Timer.builder(METRIC_DURATION)
                .description("Gateway end-to-end latency")
                .tag("tenant", tenant)
                .tag("route", route)
                .tag("status_category", statusCategory)
                .register(meterRegistry)
                .record(duration);

        pendingRequests.computeIfAbsent(tenant, k -> new LongAdder()).increment();
    }

    public void recordRateLimitRejection(String tenantId, String routeId) {
        String tenant = safe(tenantId, "anonymous");
        Counter.builder(METRIC_REJECTED)
                .description("Requests rejected because the caller exhausted its rate limit")
                .tag("tenant", tenant)
                .tag("route", safe(routeId, "unmatched"))
                .register(meterRegistry)
                .increment();

        pendingRejected.computeIfAbsent(tenant, k -> new LongAdder()).increment();
    }

    /** Flushes buffered usage counters to Redis. Counts are restored if Redis is unavailable. */
    @Scheduled(fixedDelayString = "${openmesh.metering.flush-interval:PT10S}")
    public void flush() {
        String day = today();
        Map<String, Long> requests = drain(pendingRequests);
        Map<String, Long> rejected = drain(pendingRejected);
        if (requests.isEmpty() && rejected.isEmpty()) {
            return;
        }
        Flux.concat(
                        increment(USAGE_KEY + day + ":requests", requests, pendingRequests),
                        increment(USAGE_KEY + day + ":rejected", rejected, pendingRejected))
                .then()
                .block(Duration.ofSeconds(5));
    }

    @PreDestroy
    public void flushOnShutdown() {
        try {
            flush();
        } catch (RuntimeException e) {
            log.warn("Could not flush usage counters on shutdown: {}", e.toString());
        }
    }

    /** Usage for one UTC day, highest traffic first. Values flushed in the last interval may be missing. */
    public Mono<List<TenantUsage>> getUsage(LocalDate day) {
        String base = USAGE_KEY + day;
        Mono<Map<String, Long>> requests = readHash(base + ":requests");
        Mono<Map<String, Long>> rejected = readHash(base + ":rejected");
        return Mono.zip(requests, rejected).map(tuple -> {
            Map<String, Long> req = tuple.getT1();
            Map<String, Long> rej = tuple.getT2();
            List<TenantUsage> result = new ArrayList<>();
            java.util.Set<String> tenants = new java.util.TreeSet<>(req.keySet());
            tenants.addAll(rej.keySet());
            for (String tenant : tenants) {
                result.add(new TenantUsage(tenant, req.getOrDefault(tenant, 0L), rej.getOrDefault(tenant, 0L)));
            }
            result.sort(Comparator.comparingLong(TenantUsage::requests).reversed());
            return result;
        });
    }

    public record RouteTraffic(String route, long requests, long errors) {
    }

    /**
     * Cumulative counters for this instance since start-up. The console polls this and turns the
     * difference between two snapshots into live rates (requests/s, 429/s, 5xx/s, average latency).
     */
    public record LiveSnapshot(long timestamp, long requests, long status2xx, long status4xx, long status5xx,
                               long rateLimited, double latencyTotalMs, long latencyCount, List<RouteTraffic> topRoutes) {
    }

    public LiveSnapshot liveSnapshot() {
        long total = 0;
        long s2 = 0;
        long s4 = 0;
        long s5 = 0;
        Map<String, long[]> perRoute = new HashMap<>();
        for (Counter counter : meterRegistry.find(METRIC_REQUESTS).counters()) {
            long n = (long) counter.count();
            String category = String.valueOf(counter.getId().getTag("status_category"));
            total += n;
            switch (category) {
                case "2xx" -> s2 += n;
                case "4xx" -> s4 += n;
                case "5xx" -> s5 += n;
                default -> { }
            }
            long[] route = perRoute.computeIfAbsent(String.valueOf(counter.getId().getTag("route")), k -> new long[2]);
            route[0] += n;
            if ("5xx".equals(category)) {
                route[1] += n;
            }
        }
        long rejected = 0;
        for (Counter counter : meterRegistry.find(METRIC_REJECTED).counters()) {
            rejected += (long) counter.count();
        }
        double latencyMs = 0;
        long latencyCount = 0;
        for (Timer timer : meterRegistry.find(METRIC_DURATION).timers()) {
            latencyMs += timer.totalTime(TimeUnit.MILLISECONDS);
            latencyCount += timer.count();
        }
        List<RouteTraffic> top = perRoute.entrySet().stream()
                .map(e -> new RouteTraffic(e.getKey(), e.getValue()[0], e.getValue()[1]))
                .sorted(Comparator.comparingLong(RouteTraffic::requests).reversed())
                .limit(8)
                .toList();
        return new LiveSnapshot(System.currentTimeMillis(), total, s2, s4, s5, rejected, latencyMs, latencyCount, top);
    }

    public static String today() {
        return LocalDate.now(ZoneOffset.UTC).toString();
    }

    private Mono<Void> increment(String key, Map<String, Long> counts, Map<String, LongAdder> restoreTo) {
        if (counts.isEmpty()) {
            return Mono.empty();
        }
        return Flux.fromIterable(counts.entrySet())
                .concatMap(e -> redis.opsForHash().increment(key, e.getKey(), e.getValue()))
                .then(redis.expire(key, retention))
                .then()
                .onErrorResume(e -> {
                    log.warn("Usage flush to Redis failed ({}); keeping {} counters for the next attempt",
                            e.toString(), counts.size());
                    counts.forEach((tenant, n) -> restoreTo.computeIfAbsent(tenant, k -> new LongAdder()).add(n));
                    return Mono.empty();
                });
    }

    private Mono<Map<String, Long>> readHash(String key) {
        return redis.<String, String>opsForHash().entries(key)
                .collectMap(Map.Entry::getKey, e -> Long.parseLong(e.getValue()))
                .onErrorResume(e -> {
                    log.warn("Could not read usage hash {}: {}", key, e.toString());
                    return Mono.just(new HashMap<>());
                });
    }

    /** Reads and resets each counter atomically per cell, so concurrent increments are never lost. */
    private static Map<String, Long> drain(Map<String, LongAdder> counters) {
        Map<String, Long> snapshot = new HashMap<>();
        counters.forEach((tenant, adder) -> {
            long n = adder.sumThenReset();
            if (n > 0) {
                snapshot.put(tenant, n);
            }
        });
        return snapshot;
    }

    private static String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
