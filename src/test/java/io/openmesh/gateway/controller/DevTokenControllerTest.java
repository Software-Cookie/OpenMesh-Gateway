package io.openmesh.gateway.controller;

import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import io.openmesh.gateway.config.OpenMeshProperties;
import io.openmesh.gateway.config.RsaKeyProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DevTokenControllerTest {

    private OpenMeshProperties properties;
    private RsaKeyProvider keys;
    private DevTokenController controller;

    @BeforeEach
    void setUp() {
        properties = new OpenMeshProperties();
        properties.getSecurity().getDevIssuer().setEnabled(true);
        properties.getSecurity().getDevIssuer().setMaxTtl(Duration.ofMinutes(30));
        properties.getSecurity().getJwt().setPublicKeyLocation(new ClassPathResource("certs/dev-public-key.pem"));
        properties.getSecurity().getJwt().setPrivateKeyLocation(new ClassPathResource("certs/dev-private-key.pem"));
        keys = new RsaKeyProvider(properties, new MockEnvironment());
        controller = new DevTokenController(keys, properties);
    }

    @Test
    @DisplayName("Issues an RS256 token with the configured issuer, verifiable with the public key")
    void issuesVerifiableToken() {
        var request = new DevTokenController.TokenRequest("tenant-omega", "dev-user", List.of("ROLE_ADMIN"),
                List.of("read"), "ENTERPRISE", 600);

        StepVerifier.create(controller.issue(request))
                .assertNext(res -> {
                    try {
                        SignedJWT jwt = SignedJWT.parse(res.token());
                        assertThat(jwt.verify(new RSASSAVerifier(keys.getPublicKey().orElseThrow()))).isTrue();
                        assertThat(jwt.getJWTClaimsSet().getIssuer()).isEqualTo("openmesh-gateway");
                        assertThat(jwt.getJWTClaimsSet().getStringClaim("tenant_id")).isEqualTo("tenant-omega");
                        assertThat(jwt.getJWTClaimsSet().getStringClaim("tier")).isEqualTo("ENTERPRISE");
                    } catch (Exception e) {
                        throw new AssertionError(e);
                    }
                    // 600 minutes requested, capped by max-ttl
                    assertThat(res.expiresInSeconds()).isEqualTo(30 * 60);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Defaults to least privilege: ROLE_USER with read scope")
    void defaultsToLeastPrivilege() {
        StepVerifier.create(controller.issue(null))
                .assertNext(res -> {
                    assertThat(res.roles()).containsExactly("ROLE_USER");
                    assertThat(res.scopes()).containsExactly("read");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Refuses to start under the prod profile with the committed dev keys")
    void rejectsDevKeysInProd() {
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");
        assertThatThrownBy(() -> new RsaKeyProvider(properties, prod))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("development key");
    }
}
