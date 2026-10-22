package io.openmesh.gateway.model;

import lombok.Getter;

/** Subscription plans and their token-bucket limits. */
@Getter
public enum RateLimitTier {
    FREE(5, 10),
    PRO(50, 100),
    ENTERPRISE(500, 1000),
    /** Internal / system callers. */
    UNLIMITED(10_000, 20_000);

    private final double replenishRate;
    private final double burstCapacity;

    RateLimitTier(double replenishRate, double burstCapacity) {
        this.replenishRate = replenishRate;
        this.burstCapacity = burstCapacity;
    }

    /** Unknown or missing tiers fall back to FREE (least privilege). */
    public static RateLimitTier fromString(String tierName) {
        if (tierName == null || tierName.isBlank()) {
            return FREE;
        }
        try {
            return RateLimitTier.valueOf(tierName.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return FREE;
        }
    }
}
