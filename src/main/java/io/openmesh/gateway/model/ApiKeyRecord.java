package io.openmesh.gateway.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Stored metadata for an API key. The key itself is never stored, only its SHA-256 hash.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiKeyRecord {
    private String id;
    private String name;
    private String tenantId;
    private String tier;
    @Builder.Default
    private List<String> scopes = new ArrayList<>();
    /** First characters of the key, safe to display (e.g. "omk_3fA9x2"). */
    private String prefix;
    private String createdAt;
    private String createdBy;
}
