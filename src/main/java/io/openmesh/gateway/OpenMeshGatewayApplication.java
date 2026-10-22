package io.openmesh.gateway;

import io.openmesh.gateway.config.OpenMeshProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(OpenMeshProperties.class)
public class OpenMeshGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(OpenMeshGatewayApplication.class, args);
    }
}
