package io.openmesh.gateway.config;

import io.micrometer.core.instrument.config.MeterFilter;
import io.openmesh.gateway.service.TenantMeteringService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Guards Prometheus against label explosion: once 1000 distinct tenants have been seen on a
 * metric, further new tenant values are dropped from that metric (usage metering is unaffected).
 * Common tags (application, environment) are set in application.yml under management.metrics.tags.
 */
@Configuration
public class MetricsConfig {

    private static final int MAX_TENANTS = 1000;

    @Bean
    public MeterFilter tenantRequestsCardinalityLimit() {
        return MeterFilter.maximumAllowableTags(TenantMeteringService.METRIC_REQUESTS, "tenant", MAX_TENANTS, MeterFilter.deny());
    }

    @Bean
    public MeterFilter tenantDurationCardinalityLimit() {
        return MeterFilter.maximumAllowableTags(TenantMeteringService.METRIC_DURATION, "tenant", MAX_TENANTS, MeterFilter.deny());
    }

    @Bean
    public MeterFilter tenantRejectedCardinalityLimit() {
        return MeterFilter.maximumAllowableTags(TenantMeteringService.METRIC_REJECTED, "tenant", MAX_TENANTS, MeterFilter.deny());
    }
}
