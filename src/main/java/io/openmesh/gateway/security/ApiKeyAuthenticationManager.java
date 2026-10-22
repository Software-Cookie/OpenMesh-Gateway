package io.openmesh.gateway.security;

import io.openmesh.gateway.service.ApiKeyService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import reactor.core.publisher.Mono;

/** Validates API keys against the hashed key store in Redis. */
@RequiredArgsConstructor
public class ApiKeyAuthenticationManager implements ReactiveAuthenticationManager {

    private final ApiKeyService apiKeyService;

    @Override
    public Mono<Authentication> authenticate(Authentication authentication) {
        String presented = (String) authentication.getCredentials();
        return apiKeyService.authenticate(presented)
                .onErrorMap(e -> !(e instanceof AuthenticationException),
                        e -> new AuthenticationServiceException("API key store is unavailable", e))
                .switchIfEmpty(Mono.error(new BadCredentialsException("Invalid API key")))
                .map(ApiKeyAuthenticationToken::authenticated);
    }
}
