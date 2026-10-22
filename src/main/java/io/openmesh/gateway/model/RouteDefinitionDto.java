package io.openmesh.gateway.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.route.RouteDefinition;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * Admin API representation of a route. Predicates and filters use the gateway's
 * shortcut syntax, e.g. {@code Path=/orders/**} and {@code StripPrefix=1}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RouteDefinitionDto {

    public static final String SOURCE_STATIC = "static";
    public static final String SOURCE_DYNAMIC = "dynamic";

    /** Validated by RouteValidator (on PUT the id comes from the URL). */
    private String id;

    @NotBlank(message = "uri is required")
    @Size(max = 2048, message = "uri is too long")
    private String uri;

    @NotEmpty(message = "at least one predicate is required, e.g. Path=/orders/**")
    @Size(max = 10, message = "at most 10 predicates are allowed")
    @Builder.Default
    private List<@NotBlank @Size(max = 512) String> predicates = new ArrayList<>();

    @Size(max = 20, message = "at most 20 filters are allowed")
    @Builder.Default
    private List<@NotBlank @Size(max = 512) String> filters = new ArrayList<>();

    @Builder.Default
    private int order = 0;

    @Builder.Default
    private Map<String, Object> metadata = new LinkedHashMap<>();

    /** "static" (application.yml) or "dynamic" (Redis). Read-only. */
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String source;

    public static RouteDefinitionDto fromRouteDefinition(RouteDefinition definition, String source) {
        List<String> predicateTexts = new ArrayList<>();
        if (definition.getPredicates() != null) {
            definition.getPredicates().forEach(p -> predicateTexts.add(format(p.getName(), p.getArgs())));
        }
        List<String> filterTexts = new ArrayList<>();
        if (definition.getFilters() != null) {
            definition.getFilters().forEach(f -> filterTexts.add(format(f.getName(), f.getArgs())));
        }
        return RouteDefinitionDto.builder()
                .id(definition.getId())
                .uri(definition.getUri() != null ? definition.getUri().toString() : "")
                .predicates(predicateTexts)
                .filters(filterTexts)
                .order(definition.getOrder())
                .metadata(definition.getMetadata() != null ? new LinkedHashMap<>(definition.getMetadata()) : new LinkedHashMap<>())
                .source(source)
                .build();
    }

    /**
     * Shortcut args (generated keys) render as {@code Name=a,b}; named args render as
     * {@code Name=key:value,key:value} so nothing is hidden from operators.
     */
    static String format(String name, Map<String, String> args) {
        if (args == null || args.isEmpty()) {
            return name;
        }
        boolean shortcut = args.keySet().stream().allMatch(k -> k.startsWith("_genkey_"));
        if (shortcut) {
            return name + "=" + String.join(",", args.values());
        }
        // YAML lists arrive as "key.0", "key.1": show them as key:[a,b]
        Map<String, List<String>> grouped = new LinkedHashMap<>();
        args.forEach((k, v) -> grouped.computeIfAbsent(k.replaceFirst("\\.\\d+$", ""), x -> new ArrayList<>()).add(v));
        StringJoiner joiner = new StringJoiner(",");
        grouped.forEach((k, values) -> joiner.add(k + ":" + (values.size() == 1 ? values.get(0) : values.toString().replace(" ", ""))));
        return name + "=" + joiner;
    }

    /** Converts to a gateway RouteDefinition. Throws IllegalArgumentException on malformed input. */
    public RouteDefinition toRouteDefinition() {
        RouteDefinition definition = new RouteDefinition();
        definition.setId(id);
        definition.setUri(URI.create(uri.trim()));
        definition.setOrder(order);

        List<PredicateDefinition> predicateDefinitions = new ArrayList<>();
        for (String text : predicates) {
            predicateDefinitions.add(new PredicateDefinition(text.trim()));
        }
        definition.setPredicates(predicateDefinitions);

        List<FilterDefinition> filterDefinitions = new ArrayList<>();
        if (filters != null) {
            for (String text : filters) {
                filterDefinitions.add(new FilterDefinition(text.trim()));
            }
        }
        definition.setFilters(filterDefinitions);
        definition.setMetadata(metadata != null ? new LinkedHashMap<>(metadata) : new LinkedHashMap<>());
        return definition;
    }
}
