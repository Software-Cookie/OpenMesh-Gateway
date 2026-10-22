package io.openmesh.gateway.service;

import io.openmesh.gateway.config.OpenMeshProperties;
import io.openmesh.gateway.filter.ZeroTrustIdentityFilter;
import io.openmesh.gateway.model.RouteDefinitionDto;
import io.openmesh.gateway.model.RoutePolicy;
import io.openmesh.gateway.web.ApiException;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.filter.factory.GatewayFilterFactory;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.handler.predicate.RoutePredicateFactory;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Validates dynamic routes before they are stored, so a bad route can never break routing
 * for everyone or turn the gateway into an SSRF proxy.
 */
@Component
public class RouteValidator {

    private static final double MAX_RATE = 100_000;
    private static final Pattern ROUTE_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{1,63}$");

    private final Set<String> predicateNames;
    private final Set<String> filterNames;
    private final OpenMeshProperties.Routes properties;
    private final List<Pattern> allowedHostPatterns;

    public RouteValidator(List<RoutePredicateFactory<?>> predicateFactories,
                          List<GatewayFilterFactory<?>> filterFactories,
                          OpenMeshProperties properties) {
        this.predicateNames = predicateFactories.stream().map(RoutePredicateFactory::name).collect(Collectors.toSet());
        this.filterNames = filterFactories.stream().map(GatewayFilterFactory::name).collect(Collectors.toSet());
        this.properties = properties.getRoutes();
        this.allowedHostPatterns = this.properties.getAllowedHosts().stream().map(RouteValidator::globToRegex).toList();
    }

    /** Returns the converted RouteDefinition or throws a 400 ApiException listing every problem. */
    public RouteDefinition validate(RouteDefinitionDto dto) {
        List<String> errors = new ArrayList<>();
        if (dto.getId() == null || !ROUTE_ID.matcher(dto.getId()).matches()) {
            throw ApiException.badRequest("Invalid route definition",
                    List.of("id must be 2-64 characters: letters, digits, '.', '_' or '-'"));
        }
        RouteDefinition definition = null;
        try {
            definition = dto.toRouteDefinition();
        } catch (RuntimeException e) {
            errors.add("route could not be parsed: " + e.getMessage());
        }

        if (definition != null) {
            validateUri(definition.getUri(), errors);
            for (PredicateDefinition predicate : definition.getPredicates()) {
                if (!predicateNames.contains(predicate.getName())) {
                    errors.add("unknown predicate '" + predicate.getName() + "'");
                }
            }
            for (FilterDefinition filter : definition.getFilters()) {
                if (!filterNames.contains(filter.getName())) {
                    errors.add("unknown filter '" + filter.getName() + "'");
                }
                if (touchesIdentityHeaders(filter)) {
                    errors.add("filter '" + filter.getName() + "' may not modify gateway identity headers");
                }
            }
            validateMetadata(definition.getMetadata(), errors);
        }

        if (!errors.isEmpty()) {
            throw ApiException.badRequest("Invalid route definition", errors);
        }
        return definition;
    }

    private void validateUri(URI uri, List<String> errors) {
        if (uri == null || uri.getScheme() == null) {
            errors.add("uri must be absolute, e.g. http://orders-service:8080");
            return;
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!properties.getAllowedSchemes().contains(scheme)) {
            errors.add("uri scheme '" + scheme + "' is not allowed; allowed: " + properties.getAllowedSchemes());
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            errors.add("uri must include a host");
            return;
        }
        String normalized = host.toLowerCase(Locale.ROOT).replace("[", "").replace("]", "");
        if (properties.getBlockedHosts().stream().anyMatch(b -> b.equalsIgnoreCase(normalized))
                || normalized.startsWith("169.254.")) {
            errors.add("uri host '" + host + "' is blocked");
        }
        if (!allowedHostPatterns.isEmpty() && allowedHostPatterns.stream().noneMatch(p -> p.matcher(normalized).matches())) {
            errors.add("uri host '" + host + "' is not in the allowed host list");
        }
    }

    private static boolean touchesIdentityHeaders(FilterDefinition filter) {
        String args = String.join(" ", filter.getArgs().values()).toLowerCase(Locale.ROOT);
        return ZeroTrustIdentityFilter.IDENTITY_HEADERS.stream()
                .anyMatch(h -> args.contains(h.toLowerCase(Locale.ROOT)));
    }

    private static void validateMetadata(Map<String, Object> metadata, List<String> errors) {
        if (metadata == null) {
            return;
        }
        RoutePolicy policy = RoutePolicy.from(metadata);
        for (String scope : policy.requiredScopes()) {
            if (!scope.matches("^[A-Za-z0-9:._-]{1,64}$")) {
                errors.add("invalid required scope '" + scope + "'");
            }
        }
        checkRate("rateLimit.replenishRate", policy.anonymousReplenishRate(), errors);
        checkRate("rateLimit.burstCapacity", policy.anonymousBurstCapacity(), errors);
    }

    private static void checkRate(String field, Double value, List<String> errors) {
        if (value != null && (value <= 0 || value > MAX_RATE)) {
            errors.add(field + " must be between 0 and " + (long) MAX_RATE);
        }
    }

    private static Pattern globToRegex(String glob) {
        String regex = Pattern.quote(glob.toLowerCase(Locale.ROOT)).replace("*", "\\E[^/]*\\Q");
        return Pattern.compile(regex);
    }
}
