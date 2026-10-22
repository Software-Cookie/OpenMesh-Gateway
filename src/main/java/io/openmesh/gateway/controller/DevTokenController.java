package io.openmesh.gateway.controller;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.openmesh.gateway.config.OpenMeshProperties;
import io.openmesh.gateway.config.RsaKeyProvider;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.security.interfaces.RSAPrivateKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Development-only RS256 token issuer, used by the admin console and test scripts.
 * Registered only when {@code openmesh.security.dev-issuer.enabled=true}. In production,
 * tokens come from a real identity provider (Keycloak, Auth0, Okta, Entra ID).
 */
@Slf4j
@RestController
@RequestMapping("/auth")
@ConditionalOnProperty(prefix = "openmesh.security.dev-issuer", name = "enabled", havingValue = "true")
public class DevTokenController {

    public static final String KEY_ID = "openmesh-dev-key";

    private final RSAPrivateKey privateKey;
    private final OpenMeshProperties properties;

    public DevTokenController(RsaKeyProvider keyProvider, OpenMeshProperties properties) {
        this.privateKey = keyProvider.getPrivateKey()
                .orElseThrow(() -> new IllegalStateException("Dev issuer enabled but no private key available"));
        this.properties = properties;
        log.warn("DEVELOPMENT TOKEN ISSUER IS ENABLED at POST /auth/token. Do not enable this in production.");
    }

    public record TokenRequest(
            @Pattern(regexp = "^[A-Za-z0-9._-]{2,64}$", message = "tenantId must be 2-64 characters: letters, digits, '.', '_' or '-'")
            String tenantId,
            @Pattern(regexp = "^[A-Za-z0-9._@-]{2,64}$", message = "userId must be 2-64 characters")
            String userId,
            @Size(max = 5) List<@Pattern(regexp = "^ROLE_(USER|ADMIN)$", message = "roles must be ROLE_USER or ROLE_ADMIN") String> roles,
            @Size(max = 20) List<@NotBlank @Pattern(regexp = "^[A-Za-z0-9:._-]{1,64}$", message = "invalid scope") String> scopes,
            @Pattern(regexp = "^(FREE|PRO|ENTERPRISE)$", message = "tier must be FREE, PRO or ENTERPRISE")
            String tier,
            @Min(value = 1, message = "expiresInMinutes must be at least 1")
            @Max(value = 1440, message = "expiresInMinutes must be at most 1440")
            Integer expiresInMinutes) {
    }

    public record TokenResponse(String token, String tokenType, long expiresInSeconds, String expiresAt,
                                String tenantId, String userId, List<String> roles, List<String> scopes, String tier) {
    }

    @PostMapping("/token")
    public Mono<TokenResponse> issue(@Valid @RequestBody(required = false) TokenRequest body) {
        TokenRequest request = body != null ? body : new TokenRequest(null, null, null, null, null, null);
        String tenantId = orDefault(request.tenantId(), "tenant-acme");
        String userId = orDefault(request.userId(), "dev-user");
        List<String> roles = request.roles() == null || request.roles().isEmpty() ? List.of("ROLE_USER") : request.roles();
        List<String> scopes = request.scopes() == null ? List.of("read") : request.scopes();
        String tier = orDefault(request.tier(), "PRO");

        Duration requested = Duration.ofMinutes(request.expiresInMinutes() == null ? 60 : request.expiresInMinutes());
        Duration ttl = requested.compareTo(properties.getSecurity().getDevIssuer().getMaxTtl()) > 0
                ? properties.getSecurity().getDevIssuer().getMaxTtl() : requested;

        Instant now = Instant.now();
        Instant expiresAt = now.plus(ttl);
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(properties.getSecurity().getJwt().getIssuer())
                .subject(userId)
                .claim("tenant_id", tenantId)
                .claim("roles", roles)
                .claim("scope", String.join(" ", scopes))
                .claim("tier", tier)
                .issueTime(Date.from(now))
                .notBeforeTime(Date.from(now))
                .expirationTime(Date.from(expiresAt))
                .jwtID(UUID.randomUUID().toString());
        String audience = properties.getSecurity().getJwt().getAudience();
        if (audience != null && !audience.isBlank()) {
            claims.audience(audience);
        }

        return Mono.fromCallable(() -> sign(claims.build()))
                .map(token -> new TokenResponse(token, "Bearer", ttl.toSeconds(), expiresAt.toString(),
                        tenantId, userId, roles, scopes, tier));
    }

    private String sign(JWTClaimsSet claims) throws JOSEException {
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY_ID).build(), claims);
        jwt.sign(new RSASSASigner(privateKey));
        return jwt.serialize();
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
