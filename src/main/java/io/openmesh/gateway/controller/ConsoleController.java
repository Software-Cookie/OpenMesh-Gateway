package io.openmesh.gateway.controller;

import io.openmesh.gateway.config.OpenMeshProperties;
import org.springframework.boot.info.BuildProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Public, non-sensitive settings the admin console needs before the operator signs in. */
@RestController
public class ConsoleController {

    private final OpenMeshProperties properties;
    private final String version;

    public ConsoleController(OpenMeshProperties properties, ObjectProvider<BuildProperties> buildProperties) {
        this.properties = properties;
        BuildProperties build = buildProperties.getIfAvailable();
        this.version = build != null ? build.getVersion() : "dev";
    }

    @GetMapping("/console/config")
    public Map<String, Object> config() {
        return Map.of(
                "devIssuerEnabled", properties.getSecurity().getDevIssuer().isEnabled(),
                "apiKeyHeader", properties.getSecurity().getApiKeyHeader(),
                "version", version);
    }
}
