package io.openmesh.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

/**
 * Redis wiring. Connection settings and the ReactiveStringRedisTemplate come from Spring Boot
 * auto-configuration (spring.data.redis.*); only the rate-limit script is defined here.
 */
@Configuration
public class RedisConfig {

    /** Bean name is unique so it never clashes with Spring Cloud Gateway's own rate-limiter script. */
    public static final String TOKEN_BUCKET_SCRIPT = "openmeshTokenBucketScript";

    @Bean(TOKEN_BUCKET_SCRIPT)
    @SuppressWarnings("rawtypes")
    public RedisScript<List> openmeshTokenBucketScript() {
        return RedisScript.of(new ClassPathResource("scripts/token_bucket.lua"), List.class);
    }
}
