package io.openmesh.gateway.model;

import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * Verified caller identity for one request. Built by {@code ZeroTrustIdentityFilter}
 * from a validated JWT, a valid API key, or (for public routes) the client IP.
 */
@Value
@Builder
public class TenantContext {

    public static final String ANONYMOUS = "anonymous";

    public enum AuthMethod { JWT, API_KEY, ANONYMOUS }

    String tenantId;
    String userId;
    @Builder.Default
    List<String> roles = List.of();
    @Builder.Default
    List<String> scopes = List.of();
    String tier;
    AuthMethod authMethod;
    /** Client IP as resolved by the gateway (honours trusted proxy hops). */
    String clientIp;

    public boolean isAuthenticated() {
        return authMethod != AuthMethod.ANONYMOUS;
    }

    public boolean isAdmin() {
        return roles.contains("ROLE_ADMIN");
    }
}
