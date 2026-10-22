package io.openmesh.gateway.model;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Per-route policy read from route metadata. The same keys work for YAML routes and
 * dynamic routes stored in Redis:
 *
 * <pre>
 * metadata:
 *   requiredScopes: "orders:read orders:write"   # or a list
 *   rateLimit:
 *     enabled: true
 *     replenishRate: 20      # limits for anonymous callers on this route
 *     burstCapacity: 40
 * </pre>
 *
 * Authenticated callers are always limited by their plan tier.
 */
public record RoutePolicy(List<String> requiredScopes,
                          boolean rateLimitEnabled,
                          Double anonymousReplenishRate,
                          Double anonymousBurstCapacity) {

    public static final String REQUIRED_SCOPES = "requiredScopes";
    public static final String RATE_LIMIT = "rateLimit";

    public static RoutePolicy from(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return new RoutePolicy(List.of(), true, null, null);
        }
        List<String> scopes = toStringList(metadata.get(REQUIRED_SCOPES));

        boolean enabled = true;
        Double rate = null;
        Double burst = null;
        if (metadata.get(RATE_LIMIT) instanceof Map<?, ?> rl) {
            Object enabledValue = rl.get("enabled");
            enabled = enabledValue == null || Boolean.parseBoolean(enabledValue.toString());
            rate = toDouble(rl.get("replenishRate"));
            burst = toDouble(rl.get("burstCapacity"));
        }
        return new RoutePolicy(scopes, enabled, rate, burst);
    }

    /**
     * Accepts a list, a space/comma separated string, or the index-keyed map Spring Boot
     * produces when a YAML list is bound into {@code Map<String, Object>}.
     */
    public static List<String> toStringList(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof Map<?, ?> map) {
            return toStringList(map.values());
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream()
                    .filter(v -> v != null && !v.toString().isBlank())
                    .map(v -> v.toString().trim())
                    .toList();
        }
        return Arrays.stream(value.toString().split("[,\\s]+"))
                .filter(s -> !s.isBlank())
                .toList();
    }

    private static Double toDouble(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(value.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
