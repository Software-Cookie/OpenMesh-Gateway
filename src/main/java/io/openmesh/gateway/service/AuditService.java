package io.openmesh.gateway.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.openmesh.gateway.config.OpenMeshProperties;
import io.openmesh.gateway.model.AuditEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;

/**
 * Append-only audit trail of admin actions, stored in a capped Redis list and mirrored to the log.
 * Audit failures never block the admin action itself.
 */
@Slf4j
@Service
public class AuditService {

    static final String AUDIT_KEY = "openmesh:audit";

    private final ReactiveStringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final int maxEntries;

    public AuditService(ReactiveStringRedisTemplate redis, ObjectMapper objectMapper, OpenMeshProperties properties) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.maxEntries = Math.max(10, properties.getAudit().getMaxEntries());
    }

    public Mono<Void> record(String actor, String action, String target, String detail) {
        AuditEvent event = new AuditEvent(Instant.now().toString(), actor, action, target, detail);
        log.info("AUDIT actor={} action={} target={} detail={}", actor, action, target, detail);
        String json;
        try {
            json = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            return Mono.empty();
        }
        return redis.opsForList().leftPush(AUDIT_KEY, json)
                .then(redis.opsForList().trim(AUDIT_KEY, 0, maxEntries - 1L))
                .then()
                .onErrorResume(e -> {
                    log.warn("Could not persist audit event to Redis: {}", e.toString());
                    return Mono.empty();
                });
    }

    /** Most recent events first. */
    public Flux<AuditEvent> recent(int limit) {
        int bounded = Math.min(Math.max(limit, 1), maxEntries);
        return redis.opsForList().range(AUDIT_KEY, 0, bounded - 1L)
                .flatMapSequential(json -> {
                    try {
                        return Mono.just(objectMapper.readValue(json, AuditEvent.class));
                    } catch (JsonProcessingException e) {
                        return Mono.empty();
                    }
                });
    }
}
