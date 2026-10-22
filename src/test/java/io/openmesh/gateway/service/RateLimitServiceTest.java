package io.openmesh.gateway.service;

import io.openmesh.gateway.config.OpenMeshProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings({"rawtypes", "unchecked"})
class RateLimitServiceTest {

    @Mock
    private ReactiveStringRedisTemplate redis;

    @Mock
    private RedisScript<List> script;

    private RateLimitService service(boolean failOpen) {
        OpenMeshProperties properties = new OpenMeshProperties();
        properties.getRateLimit().setFailOpen(failOpen);
        return new RateLimitService(redis, script, properties);
    }

    @Test
    @DisplayName("Maps an allowed script result and prefixes the bucket key")
    void allowed() {
        when(redis.execute(eq(script), eq(List.of("openmesh:rl:tenant:acme")), anyList()))
                .thenReturn(Flux.just(List.of(1L, 15L, 2L)));

        StepVerifier.create(service(true).checkRateLimit("tenant:acme", 10, 20, 1))
                .assertNext(r -> {
                    assertThat(r.isAllowed()).isTrue();
                    assertThat(r.getRemainingTokens()).isEqualTo(15);
                    assertThat(r.getWaitOrResetSeconds()).isEqualTo(2);
                    assertThat(r.isDegraded()).isFalse();
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Maps a rejected script result")
    void rejected() {
        when(redis.execute(eq(script), anyList(), anyList())).thenReturn(Flux.just(List.of(0L, 0L, 4L)));

        StepVerifier.create(service(true).checkRateLimit("tenant:acme", 10, 20, 1))
                .assertNext(r -> {
                    assertThat(r.isAllowed()).isFalse();
                    assertThat(r.getWaitOrResetSeconds()).isEqualTo(4);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Redis failure: fail-open lets the request through and marks it degraded")
    void failOpen() {
        when(redis.execute(eq(script), anyList(), anyList())).thenReturn(Flux.error(new RuntimeException("down")));

        StepVerifier.create(service(true).checkRateLimit("k", 10, 20, 1))
                .assertNext(r -> assertThat(r.isAllowed() && r.isDegraded()).isTrue())
                .verifyComplete();
    }

    @Test
    @DisplayName("Redis failure: fail-closed rejects the request")
    void failClosed() {
        when(redis.execute(eq(script), anyList(), anyList())).thenReturn(Flux.error(new RuntimeException("down")));

        StepVerifier.create(service(false).checkRateLimit("k", 10, 20, 1))
                .assertNext(r -> assertThat(!r.isAllowed() && r.isDegraded()).isTrue())
                .verifyComplete();
    }

    @Test
    @DisplayName("A malformed script result is treated like a Redis failure")
    void malformedResult() {
        when(redis.execute(any(RedisScript.class), anyList(), anyList())).thenReturn(Flux.just(List.of(1L)));

        StepVerifier.create(service(true).checkRateLimit("k", 10, 20, 1))
                .assertNext(r -> assertThat(r.isDegraded()).isTrue())
                .verifyComplete();
    }
}
