package io.openmesh.gateway.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionRepository;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Stores dynamic routes in a Redis hash (route id -> RouteDefinition JSON).
 * Spring Cloud Gateway merges these with the static routes from application.yml.
 * If Redis is down the gateway keeps serving the routes it already has cached.
 */
@Slf4j
@Repository
public class RedisRouteDefinitionRepository implements RouteDefinitionRepository {

    public static final String ROUTES_KEY = "openmesh:gateway:routes";

    private final ReactiveStringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public RedisRouteDefinitionRepository(ReactiveStringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    @Override
    public Flux<RouteDefinition> getRouteDefinitions() {
        return redis.<String, String>opsForHash().values(ROUTES_KEY)
                .flatMap(json -> {
                    try {
                        return Mono.just(objectMapper.readValue(json, RouteDefinition.class));
                    } catch (JsonProcessingException e) {
                        log.error("Skipping unreadable route definition in Redis: {}", e.getOriginalMessage());
                        return Mono.empty();
                    }
                })
                .onErrorResume(e -> {
                    log.warn("Could not load dynamic routes from Redis: {}", e.toString());
                    return Flux.empty();
                });
    }

    /** Like {@link #getRouteDefinitions()} but propagates Redis errors (used by the admin API). */
    public Mono<RouteDefinition> findById(String id) {
        return redis.<String, String>opsForHash().get(ROUTES_KEY, id)
                .flatMap(json -> Mono.fromCallable(() -> objectMapper.readValue(json, RouteDefinition.class)));
    }

    public Flux<RouteDefinition> findAll() {
        return redis.<String, String>opsForHash().values(ROUTES_KEY)
                .flatMap(json -> Mono.fromCallable(() -> objectMapper.readValue(json, RouteDefinition.class)));
    }

    public Mono<Long> count() {
        return redis.opsForHash().size(ROUTES_KEY);
    }

    @Override
    public Mono<Void> save(Mono<RouteDefinition> route) {
        return route.flatMap(definition -> Mono.fromCallable(() -> objectMapper.writeValueAsString(definition))
                .flatMap(json -> redis.opsForHash().put(ROUTES_KEY, definition.getId(), json))
                .doOnSuccess(ok -> log.info("Stored dynamic route '{}'", definition.getId()))
                .then());
    }

    /** Atomic create (HSETNX): emits false when a route with this id already exists. */
    public Mono<Boolean> saveIfAbsent(RouteDefinition definition) {
        return Mono.fromCallable(() -> objectMapper.writeValueAsString(definition))
                .flatMap(json -> redis.opsForHash().putIfAbsent(ROUTES_KEY, definition.getId(), json));
    }

    @Override
    public Mono<Void> delete(Mono<String> routeId) {
        return routeId.flatMap(id -> redis.opsForHash().remove(ROUTES_KEY, id)).then();
    }

    public Mono<Boolean> deleteIfExists(String id) {
        return redis.opsForHash().remove(ROUTES_KEY, id).map(removed -> removed > 0);
    }
}
