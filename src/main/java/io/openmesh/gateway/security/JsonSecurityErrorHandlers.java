package io.openmesh.gateway.security;

import io.openmesh.gateway.web.ErrorResponseWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Returns 401/403 as the shared JSON error body instead of Spring Security's empty responses. */
@Component
@RequiredArgsConstructor
public class JsonSecurityErrorHandlers {

    private final ErrorResponseWriter writer;

    public ServerAuthenticationEntryPoint entryPoint() {
        return (exchange, ex) -> unauthorized(exchange, ex);
    }

    public ServerAccessDeniedHandler accessDeniedHandler() {
        return (exchange, ex) -> writer.write(exchange, HttpStatus.FORBIDDEN, "ACCESS_DENIED",
                "You do not have permission to access this resource");
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange, AuthenticationException ex) {
        if (ex instanceof AuthenticationServiceException) {
            return writer.write(exchange, HttpStatus.SERVICE_UNAVAILABLE, "AUTH_BACKEND_UNAVAILABLE",
                    "Authentication backend is temporarily unavailable");
        }
        String message;
        if (ex instanceof InvalidBearerTokenException) {
            message = "Bearer token is invalid or expired";
            exchange.getResponse().getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\"");
        } else if (ex != null && "Invalid API key".equals(ex.getMessage())) {
            message = "API key is invalid or revoked";
        } else {
            message = "Authentication required: send 'Authorization: Bearer <jwt>' or an API key";
            exchange.getResponse().getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        }
        return writer.write(exchange, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", message);
    }
}
