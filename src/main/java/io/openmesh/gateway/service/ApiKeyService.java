package io.openmesh.gateway.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.openmesh.gateway.model.ApiKeyCreateRequest;
import io.openmesh.gateway.model.ApiKeyRecord;
import io.openmesh.gateway.web.ApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

/**
 * Machine-to-machine API keys.
 * <ul>
 *   <li>Keys look like {@code omk_<43 random chars>} (256 bits of entropy).</li>
 *   <li>Only the SHA-256 hash is stored; the plaintext is shown once at creation.</li>
 *   <li>Each key is bound to a tenant, a plan tier and a set of scopes.</li>
 * </ul>
 */
@Slf4j
@Service
public class ApiKeyService {

    static final String KEYS_BY_HASH = "openmesh:apikeys";
    static final String HASH_BY_ID = "openmesh:apikeys:index";
    private static final String KEY_PREFIX = "omk_";

    private final ReactiveStringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final SecureRandom random = new SecureRandom();

    public ApiKeyService(ReactiveStringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    public record CreatedKey(String apiKey, ApiKeyRecord record) {
    }

    public Mono<CreatedKey> create(ApiKeyCreateRequest request, String actor) {
        byte[] secret = new byte[32];
        random.nextBytes(secret);
        String apiKey = KEY_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        String hash = sha256(apiKey);

        ApiKeyRecord record = ApiKeyRecord.builder()
                .id("key_" + hash.substring(0, 12))
                .name(request.name().trim())
                .tenantId(request.tenantId())
                .tier(request.tier() == null ? "FREE" : request.tier())
                .scopes(request.scopes() == null ? List.of() : List.copyOf(request.scopes()))
                .prefix(apiKey.substring(0, 10))
                .createdAt(Instant.now().toString())
                .createdBy(actor)
                .build();

        return Mono.fromCallable(() -> objectMapper.writeValueAsString(record))
                .flatMap(json -> redis.opsForHash().put(KEYS_BY_HASH, hash, json))
                .then(redis.opsForHash().put(HASH_BY_ID, record.getId(), hash))
                .thenReturn(new CreatedKey(apiKey, record));
    }

    public Flux<ApiKeyRecord> list() {
        return redis.<String, String>opsForHash().values(KEYS_BY_HASH)
                .flatMap(this::parse)
                .sort((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()));
    }

    public Mono<Long> count() {
        return redis.opsForHash().size(KEYS_BY_HASH);
    }

    /** Revokes a key by id. Errors with 404 when the id is unknown. */
    public Mono<ApiKeyRecord> revoke(String id) {
        return redis.<String, String>opsForHash().get(HASH_BY_ID, id)
                .switchIfEmpty(Mono.error(ApiException.notFound("API key not found: " + id)))
                .flatMap(hash -> redis.<String, String>opsForHash().get(KEYS_BY_HASH, hash)
                        .flatMap(this::parse)
                        .flatMap(record -> redis.opsForHash().remove(KEYS_BY_HASH, hash)
                                .then(redis.opsForHash().remove(HASH_BY_ID, id))
                                .thenReturn(record)));
    }

    /** Looks up the record for a presented key; empty when the key is unknown or malformed. */
    public Mono<ApiKeyRecord> authenticate(String presentedKey) {
        if (presentedKey == null || !presentedKey.startsWith(KEY_PREFIX) || presentedKey.length() > 100) {
            return Mono.empty();
        }
        return redis.<String, String>opsForHash().get(KEYS_BY_HASH, sha256(presentedKey))
                .flatMap(this::parse);
    }

    private Mono<ApiKeyRecord> parse(String json) {
        try {
            return Mono.just(objectMapper.readValue(json, ApiKeyRecord.class));
        } catch (JsonProcessingException e) {
            log.error("Corrupt API key record in Redis", e);
            return Mono.empty();
        }
    }

    static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
