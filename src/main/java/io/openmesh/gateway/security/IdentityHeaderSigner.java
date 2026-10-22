package io.openmesh.gateway.security;

import io.openmesh.gateway.config.OpenMeshProperties;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.Optional;

/**
 * Signs the identity headers forwarded to downstream services with HMAC-SHA256.
 * A downstream service recomputes the signature with the shared secret to prove the headers were
 * set by the gateway and not by a caller that bypassed it.
 *
 * <p>Canonical string (fields joined with '\n'):
 * {@code userId, tenantId, roles, scopes, authMethod, timestamp}
 */
@Component
public class IdentityHeaderSigner {

    private static final String ALGORITHM = "HmacSHA256";
    static final int MIN_PROD_SECRET_LENGTH = 32;

    private final byte[] secret;

    public IdentityHeaderSigner(OpenMeshProperties properties, Environment environment) {
        String configured = properties.getSecurity().getIdentitySigningSecret();
        boolean prod = environment.acceptsProfiles(Profiles.of("prod"));

        if (configured != null && configured.contains("${")) {
            // An unresolved placeholder would otherwise become a publicly known secret
            throw new IllegalStateException("openmesh.security.identity-signing-secret is an unresolved placeholder: "
                    + "set OPENMESH_IDENTITY_SIGNING_SECRET");
        }
        if (prod && (configured == null || configured.length() < MIN_PROD_SECRET_LENGTH)) {
            throw new IllegalStateException("The 'prod' profile requires OPENMESH_IDENTITY_SIGNING_SECRET with at least "
                    + MIN_PROD_SECRET_LENGTH + " characters");
        }
        this.secret = configured == null || configured.isBlank() ? null : configured.getBytes(StandardCharsets.UTF_8);
    }

    public boolean isEnabled() {
        return secret != null;
    }

    public Optional<String> sign(String userId, String tenantId, String roles, String scopes,
                                 String authMethod, String timestamp) {
        if (secret == null) {
            return Optional.empty();
        }
        String canonical = String.join("\n", userId, tenantId, roles, scopes, authMethod, timestamp);
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            byte[] signature = mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8));
            return Optional.of("v1=" + Base64.getUrlEncoder().withoutPadding().encodeToString(signature));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Unable to sign identity headers", e);
        }
    }
}
