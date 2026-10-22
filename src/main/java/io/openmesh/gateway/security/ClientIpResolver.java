package io.openmesh.gateway.security;

import io.openmesh.gateway.config.OpenMeshProperties;
import org.springframework.cloud.gateway.support.ipresolver.RemoteAddressResolver;
import org.springframework.cloud.gateway.support.ipresolver.XForwardedRemoteAddressResolver;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import java.net.InetSocketAddress;

/**
 * Resolves the real client IP. X-Forwarded-For is only trusted for the configured number of
 * proxy hops; otherwise any client could fake its IP and dodge per-IP rate limits.
 */
@Component
public class ClientIpResolver {

    private final RemoteAddressResolver resolver;

    public ClientIpResolver(OpenMeshProperties properties) {
        int hops = properties.getRateLimit().getTrustedProxyHops();
        this.resolver = hops > 0
                ? XForwardedRemoteAddressResolver.maxTrustedIndex(hops)
                : new RemoteAddressResolver() { };
    }

    public String resolve(ServerWebExchange exchange) {
        InetSocketAddress address = resolver.resolve(exchange);
        if (address == null) {
            return "unknown";
        }
        return address.getAddress() != null ? address.getAddress().getHostAddress() : address.getHostString();
    }
}
