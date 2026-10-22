package io.openmesh.gateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * All OpenMesh-specific settings, bound from the {@code openmesh.*} namespace.
 * Defaults here are the production-safe ones; the {@code dev} profile relaxes them.
 */
@Data
@ConfigurationProperties(prefix = "openmesh")
public class OpenMeshProperties {

    private final Security security = new Security();
    private final RateLimit rateLimit = new RateLimit();
    private final Routes routes = new Routes();
    private final Metering metering = new Metering();
    private final Audit audit = new Audit();

    @Data
    public static class Security {
        private final Jwt jwt = new Jwt();
        private final DevIssuer devIssuer = new DevIssuer();

        /** Path patterns reachable without authentication (gateway traffic). */
        private List<String> publicPaths = new ArrayList<>(List.of("/public/**"));

        /**
         * Shared secret used to HMAC-sign the identity headers sent to downstream services.
         * Downstream services verify X-Gateway-Signature to prove the request came through the gateway.
         * Empty disables signing.
         */
        private String identitySigningSecret = "";

        /** Header clients use to present an API key. */
        private String apiKeyHeader = "X-API-Key";
    }

    @Data
    public static class Jwt {
        /** Expected "iss" claim. Tokens from any other issuer are rejected. */
        private String issuer = "openmesh-gateway";

        /** Optional expected "aud" value. Empty disables the audience check. */
        private String audience = "";

        /** JWKS endpoint of an external identity provider (Keycloak, Auth0, Okta...). Takes precedence over the public key. */
        private String jwkSetUri = "";

        /** PEM-encoded RSA public key used to verify RS256 tokens when no JWKS URI is set. */
        private Resource publicKeyLocation;

        /** PEM-encoded RSA private key, only needed by the development token issuer. */
        private Resource privateKeyLocation;

        /** Allowed clock skew when validating exp/nbf. */
        private Duration clockSkew = Duration.ofSeconds(30);

        /** Claim holding the tenant id. Dot paths are supported (e.g. "org.id"). */
        private String tenantClaim = "tenant_id";

        /** Claim holding the user's roles (e.g. "realm_access.roles" for Keycloak). */
        private String rolesClaim = "roles";

        /** Claim holding the plan tier (FREE, PRO, ENTERPRISE). Missing means FREE. */
        private String tierClaim = "tier";
    }

    @Data
    public static class DevIssuer {
        /** Enables POST /auth/token. Never enable in production: anyone could mint admin tokens. */
        private boolean enabled = false;

        /** Upper bound for the lifetime of minted tokens. */
        private Duration maxTtl = Duration.ofHours(12);
    }

    @Data
    public static class RateLimit {
        private boolean enabled = true;

        /** When Redis is unreachable: true lets traffic through, false rejects it with 503. */
        private boolean failOpen = true;

        /** Timeout for a single Redis rate-limit evaluation. */
        private Duration redisTimeout = Duration.ofMillis(250);

        /** Default limit for unauthenticated callers (keyed by client IP), unless the route overrides it. */
        private double anonymousReplenishRate = 5;
        private double anonymousBurstCapacity = 10;

        /**
         * Number of trusted reverse proxies in front of the gateway. 0 means the TCP peer address
         * is used as the client IP; N means the N-th address from the right of X-Forwarded-For.
         */
        private int trustedProxyHops = 0;
    }

    @Data
    public static class Routes {
        /** URI schemes dynamic routes may target. */
        private List<String> allowedSchemes = new ArrayList<>(List.of("http", "https", "lb"));

        /**
         * Host patterns (glob, e.g. "*.svc.cluster.local") dynamic routes may target.
         * Empty allows any host except the blocked ones.
         */
        private List<String> allowedHosts = new ArrayList<>();

        /** Hosts that are never valid route targets (cloud metadata endpoints, SSRF protection). */
        private List<String> blockedHosts = new ArrayList<>(List.of(
                "169.254.169.254", "metadata.google.internal", "metadata", "100.100.100.200", "fd00:ec2::254"));
    }

    @Data
    public static class Metering {
        /** How often per-tenant usage counters are flushed from memory to Redis. */
        private Duration flushInterval = Duration.ofSeconds(10);

        /** How long daily usage records are kept in Redis. */
        private Duration retention = Duration.ofDays(35);
    }

    @Data
    public static class Audit {
        /** Maximum number of admin audit events kept in Redis. */
        private int maxEntries = 1000;
    }
}
