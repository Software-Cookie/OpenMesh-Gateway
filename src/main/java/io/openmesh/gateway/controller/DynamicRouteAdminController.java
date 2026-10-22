package io.openmesh.gateway.controller;

import io.openmesh.gateway.model.RouteDefinitionDto;
import io.openmesh.gateway.service.DynamicRouteService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.security.Principal;
import java.util.Map;

/** Route management API. Requires ROLE_ADMIN (see SecurityConfig). Every change is audited. */
@RestController
@RequestMapping("/admin/v1/routes")
@RequiredArgsConstructor
public class DynamicRouteAdminController {

    private final DynamicRouteService routeService;

    @GetMapping
    public Flux<RouteDefinitionDto> listRoutes() {
        return routeService.getAllRoutes();
    }

    @GetMapping("/{routeId}")
    public Mono<RouteDefinitionDto> getRoute(@PathVariable String routeId) {
        return routeService.getRouteById(routeId);
    }

    @PostMapping
    public Mono<ResponseEntity<RouteDefinitionDto>> createRoute(@Valid @RequestBody RouteDefinitionDto route,
                                                                Principal principal) {
        return routeService.createRoute(route, principal.getName())
                .map(created -> ResponseEntity.status(HttpStatus.CREATED).body(created));
    }

    @PutMapping("/{routeId}")
    public Mono<RouteDefinitionDto> upsertRoute(@PathVariable String routeId,
                                                @Valid @RequestBody RouteDefinitionDto route,
                                                Principal principal) {
        return routeService.upsertRoute(routeId, route, principal.getName());
    }

    @DeleteMapping("/{routeId}")
    public Mono<ResponseEntity<Void>> deleteRoute(@PathVariable String routeId, Principal principal) {
        return routeService.deleteRoute(routeId, principal.getName())
                .thenReturn(ResponseEntity.noContent().build());
    }

    /** Forces every gateway instance to reload its routing table. */
    @PostMapping("/refresh")
    public Mono<Map<String, String>> refreshRoutes(Principal principal) {
        return routeService.refreshRoutes(principal.getName())
                .thenReturn(Map.of("status", "SUCCESS", "message", "Route reload broadcast to all gateway instances"));
    }
}
