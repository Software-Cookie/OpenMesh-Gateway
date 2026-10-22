package io.openmesh.gateway.security;

import io.openmesh.gateway.model.RoutePolicy;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.Map;

/** Reads claims by dot path so the gateway works with any identity provider's token layout. */
public final class JwtClaims {

    private JwtClaims() {
    }

    public static Object get(Jwt jwt, String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        Object current = jwt.getClaims();
        for (String part : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(part);
        }
        return current;
    }

    public static String getString(Jwt jwt, String path) {
        Object value = get(jwt, path);
        return value == null || value.toString().isBlank() ? null : value.toString();
    }

    public static List<String> getList(Jwt jwt, String path) {
        return RoutePolicy.toStringList(get(jwt, path));
    }

    /** OAuth2 scopes from "scope" (space separated) or "scp" (list). */
    public static List<String> scopes(Jwt jwt) {
        List<String> scopes = getList(jwt, "scope");
        return scopes.isEmpty() ? getList(jwt, "scp") : scopes;
    }

    public static List<String> roles(Jwt jwt, String rolesClaim) {
        return getList(jwt, rolesClaim).stream()
                .map(r -> r.startsWith("ROLE_") ? r : "ROLE_" + r.toUpperCase())
                .toList();
    }
}
