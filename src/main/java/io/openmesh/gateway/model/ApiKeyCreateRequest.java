package io.openmesh.gateway.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

public record ApiKeyCreateRequest(
        @NotBlank(message = "name is required")
        @Size(max = 80, message = "name must be at most 80 characters")
        String name,

        @NotBlank(message = "tenantId is required")
        @Pattern(regexp = "^[A-Za-z0-9._-]{2,64}$", message = "tenantId must be 2-64 characters: letters, digits, '.', '_' or '-'")
        String tenantId,

        @Pattern(regexp = "^(FREE|PRO|ENTERPRISE)$", message = "tier must be FREE, PRO or ENTERPRISE")
        String tier,

        @Size(max = 20, message = "at most 20 scopes are allowed")
        List<@Pattern(regexp = "^[A-Za-z0-9:._-]{1,64}$", message = "invalid scope") String> scopes) {
}
