package io.openmesh.gateway.security;

import io.openmesh.gateway.config.OpenMeshProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdentityHeaderSignerTest {

    static IdentityHeaderSigner signer(String secret, String... profiles) {
        OpenMeshProperties properties = new OpenMeshProperties();
        properties.getSecurity().setIdentitySigningSecret(secret);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        return new IdentityHeaderSigner(properties, environment);
    }

    @Test
    @DisplayName("Produces a stable v1 HMAC that changes when any identity field changes")
    void signsDeterministically() {
        IdentityHeaderSigner s = signer("secret");
        String a = s.sign("alice", "acme", "ROLE_USER", "read", "JWT", "1").orElseThrow();
        String b = s.sign("alice", "acme", "ROLE_USER", "read", "JWT", "1").orElseThrow();
        String tampered = s.sign("alice", "evil", "ROLE_USER", "read", "JWT", "1").orElseThrow();

        assertThat(a).startsWith("v1=").isEqualTo(b).isNotEqualTo(tampered);
        assertThat(signer("other-secret").sign("alice", "acme", "ROLE_USER", "read", "JWT", "1")).isNotEqualTo(Optional.of(a));
    }

    @Test
    @DisplayName("Signing is disabled when no secret is configured (non-prod)")
    void disabledWithoutSecret() {
        IdentityHeaderSigner s = signer("");
        assertThat(s.isEnabled()).isFalse();
        assertThat(s.sign("a", "b", "c", "d", "e", "f")).isEmpty();
    }

    @Test
    @DisplayName("Refuses unresolved placeholders and weak secrets in prod")
    void failsFastOnBadSecrets() {
        assertThatThrownBy(() -> signer("${OPENMESH_IDENTITY_SIGNING_SECRET}")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> signer("", "prod")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> signer("too-short", "prod")).isInstanceOf(IllegalStateException.class);
        assertThat(signer("x".repeat(32), "prod").isEnabled()).isTrue();
    }
}
