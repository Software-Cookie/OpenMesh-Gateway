package io.openmesh.gateway.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/** One admin action (route or API key change), kept in a capped Redis list. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuditEvent(String timestamp, String actor, String action, String target, String detail) {
}
