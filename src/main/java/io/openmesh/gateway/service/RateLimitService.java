package io.openmesh.gateway.service;

import io.openmesh.gateway.config.OpenMeshProperties;
import io.openmesh.gateway.config.RedisConfig;
import io.openmesh.gateway.model.RateLimitResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

/**
 * Distributed token-bucket rate limiting backed by an atomic Redis Lua script.
 * All gateway instances share the same buckets, so limits hold across a cluster.
 */
@Slf4j
@Service
public class RateLimitService {

    public static final String KEY_PREFIX = "openmesh:rl:";

    private final ReactiveStringRedisTemplate redis;
    @SuppressWarnings("rawtypes")
    private final RedisScript<List> script;
    private final OpenMeshProperties.RateLimit properties;

    @SuppressWarnings("rawtypes")
    public RateLimitService(ReactiveStringRedisTemplate redis,
                            @Qualifier(RedisConfig.TOKEN_BUCKET_SCRIPT) RedisScript<List> script,
                            OpenMeshProperties properties) {
        this.redis = redis;
        this.script = script;
        this.properties = properties.getRateLimit();
    }

    /**
     * @param bucketKey       caller identity, e.g. "tenant:acme" or "anon:public-route:10.0.0.7"
     * @param replenishRate   tokens added per second
     * @param burstCapacity   maximum bucket size
     * @param requestedTokens tokens this request costs
     */
    public Mono<RateLimitResult> checkRateLimit(String bucketKey, double replenishRate, double burstCapacity,
                                                int requestedTokens) {
        List<String> keys = List.of(KEY_PREFIX + bucketKey);
        List<String> args = List.of(
                String.valueOf(replenishRate),
                String.valueOf(burstCapacity),
                String.valueOf(Math.max(1, requestedTokens)));

        return redis.execute(script, keys, args)
                .reduce(new ArrayList<Object>(), (acc, part) -> {
                    acc.addAll(part);
                    return acc;
                })
                .timeout(properties.getRedisTimeout())
                .map(values -> RateLimitResult.builder()
                        .allowed(asLong(values, 0) == 1L)
                        .remainingTokens(asLong(values, 1))
                        .waitOrResetSeconds(asLong(values, 2))
                        .replenishRate(replenishRate)
                        .burstCapacity(burstCapacity)
                        .build())
                .onErrorResume(e -> {
                    log.warn("Rate limit check failed for '{}' ({}); policy is fail-{}", bucketKey,
                            e.toString(), properties.isFailOpen() ? "open" : "closed");
                    return Mono.just(RateLimitResult.builder()
                            .allowed(properties.isFailOpen())
                            .remainingTokens(properties.isFailOpen() ? (long) burstCapacity : 0)
                            .waitOrResetSeconds(properties.isFailOpen() ? 0 : 1)
                            .replenishRate(replenishRate)
                            .burstCapacity(burstCapacity)
                            .degraded(true)
                            .build());
                });
    }

    private static long asLong(List<Object> values, int index) {
        if (values.size() <= index || values.get(index) == null) {
            throw new IllegalStateException("Unexpected rate-limit script result: " + values);
        }
        Object value = values.get(index);
        return value instanceof Number n ? n.longValue() : Long.parseLong(value.toString());
    }
}
