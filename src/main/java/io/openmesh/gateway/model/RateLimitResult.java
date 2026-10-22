package io.openmesh.gateway.model;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class RateLimitResult {
    boolean allowed;
    long remainingTokens;
    /** When allowed: seconds until the bucket is full again. When rejected: seconds to wait before retrying. */
    long waitOrResetSeconds;
    double replenishRate;
    double burstCapacity;
    /** True when Redis could not be reached and the fail-open/fail-closed policy decided the outcome. */
    boolean degraded;
}
