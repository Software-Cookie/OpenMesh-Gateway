package io.openmesh.gateway.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;

/**
 * Loads the RSA key pair used for RS256 JWTs.
 *
 * <ul>
 *   <li>The public key verifies incoming tokens (unless a JWKS URI is configured).</li>
 *   <li>The private key is only used by the development token issuer.</li>
 *   <li>An ephemeral key pair is generated only when the dev issuer is on and no keys are configured.</li>
 *   <li>Startup fails if the committed development keys are used under the {@code prod} profile.</li>
 * </ul>
 */
@Slf4j
@Component
public class RsaKeyProvider {

    private final RSAPublicKey publicKey;
    private final RSAPrivateKey privateKey;

    public RsaKeyProvider(OpenMeshProperties properties, Environment environment) {
        OpenMeshProperties.Jwt jwt = properties.getSecurity().getJwt();
        boolean devIssuer = properties.getSecurity().getDevIssuer().isEnabled();
        boolean prod = Arrays.asList(environment.getActiveProfiles()).contains("prod");

        rejectDevKeysInProd(prod, jwt.getPublicKeyLocation());
        rejectDevKeysInProd(prod, jwt.getPrivateKeyLocation());

        RSAPublicKey pub = loadIfPresent(jwt.getPublicKeyLocation(), RsaKeyProvider::parsePublicKey);
        RSAPrivateKey priv = devIssuer ? loadIfPresent(jwt.getPrivateKeyLocation(), RsaKeyProvider::parsePrivateKey) : null;

        if (devIssuer && (pub == null || priv == null)) {
            log.warn("Dev token issuer enabled without a configured key pair: generating an ephemeral RSA-2048 key pair. "
                    + "Tokens will stop working after a restart and will not be accepted by other instances.");
            KeyPair pair = generateKeyPair();
            pub = (RSAPublicKey) pair.getPublic();
            priv = (RSAPrivateKey) pair.getPrivate();
        }

        if (pub != null && priv != null) {
            verifyPairMatches(pub, priv);
        }

        this.publicKey = pub;
        this.privateKey = priv;
    }

    /** Public key for verifying tokens; empty when the gateway relies on a JWKS endpoint. */
    public Optional<RSAPublicKey> getPublicKey() {
        return Optional.ofNullable(publicKey);
    }

    /** Private key for the dev issuer; empty when the dev issuer is disabled. */
    public Optional<RSAPrivateKey> getPrivateKey() {
        return Optional.ofNullable(privateKey);
    }

    private static void rejectDevKeysInProd(boolean prod, Resource resource) {
        if (prod && resource != null && resource.getFilename() != null && resource.getFilename().startsWith("dev-")) {
            throw new IllegalStateException("Refusing to start: development key '" + resource.getFilename()
                    + "' is configured under the 'prod' profile. Set OPENMESH_JWT_PUBLIC_KEY_LOCATION or OPENMESH_JWT_JWK_SET_URI.");
        }
    }

    private static <T> T loadIfPresent(Resource resource, PemParser<T> parser) {
        if (resource == null) {
            return null;
        }
        if (!resource.exists()) {
            throw new IllegalStateException("Configured RSA key not found: " + resource.getDescription());
        }
        try (InputStream in = resource.getInputStream()) {
            T key = parser.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            log.info("Loaded RSA key from {}", resource.getDescription());
            return key;
        } catch (IOException | GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Invalid RSA key at " + resource.getDescription() + ": " + e.getMessage(), e);
        }
    }

    private static void verifyPairMatches(RSAPublicKey pub, RSAPrivateKey priv) {
        try {
            byte[] probe = "openmesh-key-check".getBytes(StandardCharsets.UTF_8);
            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(priv);
            signer.update(probe);
            byte[] sig = signer.sign();
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(pub);
            verifier.update(probe);
            if (!verifier.verify(sig)) {
                throw new IllegalStateException("RSA public and private keys do not belong to the same key pair");
            }
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Unable to verify RSA key pair", e);
        }
    }

    private static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Unable to generate RSA key pair", e);
        }
    }

    static RSAPublicKey parsePublicKey(String pem) throws GeneralSecurityException {
        byte[] der = decodePem(pem, "PUBLIC KEY");
        return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
    }

    static RSAPrivateKey parsePrivateKey(String pem) throws GeneralSecurityException {
        byte[] der = decodePem(pem, "PRIVATE KEY");
        return (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    private static byte[] decodePem(String pem, String type) {
        String body = pem
                .replace("-----BEGIN " + type + "-----", "")
                .replace("-----END " + type + "-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(body);
    }

    @FunctionalInterface
    private interface PemParser<T> {
        T parse(String pem) throws GeneralSecurityException;
    }
}
