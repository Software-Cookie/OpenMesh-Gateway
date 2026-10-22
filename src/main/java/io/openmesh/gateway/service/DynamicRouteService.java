package io.openmesh.gateway.service;

import io.openmesh.gateway.model.RouteDefinitionDto;
import io.openmesh.gateway.repository.RedisRouteDefinitionRepository;
import io.openmesh.gateway.web.ApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.config.GatewayProperties;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Optional;

/**
 * Route management for the admin API.
 * Static routes (application.yml) are read-only; dynamic routes live in Redis and can be
 * created, replaced and deleted at runtime without restarting any gateway instance.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DynamicRouteService {

    private final RedisRouteDefinitionRepository repository;
    private final GatewayProperties gatewayProperties;
    private final RouteValidator validator;
    private final RouteRefreshBroadcaster broadcaster;
    private final AuditService audit;

    /** Static routes first, then dynamic routes. */
    public Flux<RouteDefinitionDto> getAllRoutes() {
        Flux<RouteDefinitionDto> statics = Flux.fromIterable(gatewayProperties.getRoutes())
                .map(r -> RouteDefinitionDto.fromRouteDefinition(r, RouteDefinitionDto.SOURCE_STATIC));
        Flux<RouteDefinitionDto> dynamics = repository.findAll()
                .map(r -> RouteDefinitionDto.fromRouteDefinition(r, RouteDefinitionDto.SOURCE_DYNAMIC))
                .sort((a, b) -> a.getId().compareToIgnoreCase(b.getId()))
                .onErrorMap(e -> !(e instanceof ApiException), e -> ApiException.unavailable("Route store (Redis) is unavailable"));
        return statics.concatWith(dynamics);
    }

    public Mono<RouteDefinitionDto> getRouteById(String routeId) {
        return Mono.justOrEmpty(findStatic(routeId))
                .map(r -> RouteDefinitionDto.fromRouteDefinition(r, RouteDefinitionDto.SOURCE_STATIC))
                .switchIfEmpty(repository.findById(routeId)
                        .map(r -> RouteDefinitionDto.fromRouteDefinition(r, RouteDefinitionDto.SOURCE_DYNAMIC)))
                .switchIfEmpty(Mono.error(ApiException.notFound("Route not found: " + routeId)));
    }

    public Mono<RouteDefinitionDto> createRoute(RouteDefinitionDto dto, String actor) {
        rejectStatic(dto.getId());
        RouteDefinition definition = validator.validate(dto);
        return repository.saveIfAbsent(definition)
                .flatMap(created -> created
                        ? announce(definition, actor, "ROUTE_CREATED")
                        : Mono.error(ApiException.conflict("Route already exists: " + dto.getId())));
    }

    /** Creates or replaces a dynamic route. */
    public Mono<RouteDefinitionDto> upsertRoute(String routeId, RouteDefinitionDto dto, String actor) {
        dto.setId(routeId);
        rejectStatic(routeId);
        RouteDefinition definition = validator.validate(dto);
        return persist(definition, actor, "ROUTE_UPSERTED");
    }

    public Mono<Void> deleteRoute(String routeId, String actor) {
        rejectStatic(routeId);
        return repository.deleteIfExists(routeId)
                .flatMap(deleted -> deleted
                        ? broadcaster.broadcast().then(audit.record(actor, "ROUTE_DELETED", routeId, null))
                        : Mono.error(ApiException.notFound("Route not found: " + routeId)));
    }

    public Mono<Void> refreshRoutes(String actor) {
        return broadcaster.broadcast().then(audit.record(actor, "ROUTES_REFRESHED", "*", null));
    }

    public Mono<long[]> countRoutes() {
        long statics = gatewayProperties.getRoutes().size();
        return repository.count().onErrorReturn(-1L).map(dynamic -> new long[]{statics, dynamic});
    }

    private Mono<RouteDefinitionDto> persist(RouteDefinition definition, String actor, String action) {
        return repository.save(Mono.just(definition)).then(announce(definition, actor, action));
    }

    /** Reloads every instance, writes the audit entry and returns the stored route. */
    private Mono<RouteDefinitionDto> announce(RouteDefinition definition, String actor, String action) {
        return broadcaster.broadcast()
                .then(audit.record(actor, action, definition.getId(), definition.getUri().toString()))
                .thenReturn(RouteDefinitionDto.fromRouteDefinition(definition, RouteDefinitionDto.SOURCE_DYNAMIC))
                .doOnSuccess(r -> log.info("{} '{}' by {}", action, definition.getId(), actor));
    }

    private Optional<RouteDefinition> findStatic(String routeId) {
        return gatewayProperties.getRoutes().stream().filter(r -> r.getId().equals(routeId)).findFirst();
    }

    private void rejectStatic(String routeId) {
        if (findStatic(routeId).isPresent()) {
            throw ApiException.conflict("Route '" + routeId + "' is defined in application.yml and is read-only");
        }
    }
}
