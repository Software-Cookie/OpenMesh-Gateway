package io.openmesh.gateway.model;

/** Billable usage for one tenant on one UTC day. */
public record TenantUsage(String tenantId, long requests, long rejected) {
}
