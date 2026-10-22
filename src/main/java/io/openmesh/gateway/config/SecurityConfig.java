package io.openmesh.gateway.config;

import io.openmesh.gateway.security.ApiKeyAuthenticationManager;
import io.openmesh.gateway.security.ApiKeyAuthenticationToken;
import io.openmesh.gateway.security.JsonSecurityErrorHandlers;
import io.openmesh.gateway.security.JwtClaims;
import io.openmesh.gateway.service.ApiKeyService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.AuthenticationWebFilter;
import org.springframework.security.web.server.authentication.ServerAuthenticationEntryPointFailureHandler;
import org.springframework.security.web.server.header.ReferrerPolicyServerHttpHeadersWriter;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

/**
 * Zero-trust security model:
 * <ul>
 *   <li>Every gateway route requires a valid RS256 JWT or API key, except configured public paths.</li>
 *   <li>The admin API and non-public actuator endpoints require ROLE_ADMIN.</li>
 *   <li>Tokens are checked for signature, expiry, issuer and (optionally) audience.</li>
 *   <li>Stateless: no sessions, no cookies, so CSRF protection is not needed.</li>
 * </ul>
 */
@Slf4j
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    private static final String CSP = String.join("; ",
            "default-src 'self'",
            "script-src 'self'",
            "style-src 'self'",
            "font-src 'self'",
            "img-src 'self' data:",
            "connect-src 'self'",
            "frame-ancestors 'none'",
            "base-uri 'self'",
            "form-action 'self'");

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http,
                                                         OpenMeshProperties properties,
                                                         ReactiveJwtDecoder jwtDecoder,
                                                         JsonSecurityErrorHandlers errorHandlers,
                                                         ApiKeyService apiKeyService) {
        String[] publicGatewayPaths = properties.getSecurity().getPublicPaths().toArray(String[]::new);

        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives(CSP))
                        .referrerPolicy(ref -> ref.policy(ReferrerPolicyServerHttpHeadersWriter.ReferrerPolicy.NO_REFERRER)))
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers(HttpMethod.OPTIONS).permitAll()
                        // Admin console static assets (data still requires an admin token)
                        .pathMatchers("/", "/index.html", "/favicon.svg", "/assets/**", "/console/config").permitAll()
                        // Liveness/readiness probes and Prometheus scraping
                        .pathMatchers("/actuator/health", "/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                        // Only registered when openmesh.security.dev-issuer.enabled=true
                        .pathMatchers(HttpMethod.POST, "/auth/token").permitAll()
                        .pathMatchers("/fallback/**").permitAll()
                        .pathMatchers("/admin/**", "/actuator/**").hasAuthority("ROLE_ADMIN")
                        .pathMatchers(publicGatewayPaths).permitAll()
                        .anyExchange().authenticated())
                .addFilterAt(apiKeyFilter(properties, apiKeyService, errorHandlers), SecurityWebFiltersOrder.AUTHENTICATION)
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt
                                .jwtDecoder(jwtDecoder)
                                .jwtAuthenticationConverter(jwtAuthenticationConverter(properties)))
                        .authenticationEntryPoint(errorHandlers.entryPoint())
                        .accessDeniedHandler(errorHandlers.accessDeniedHandler()))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(errorHandlers.entryPoint())
                        .accessDeniedHandler(errorHandlers.accessDeniedHandler()))
                .build();
    }

    /** Authenticates requests carrying the API key header. Only runs when the header is present. */
    private AuthenticationWebFilter apiKeyFilter(OpenMeshProperties properties, ApiKeyService apiKeyService,
                                                 JsonSecurityErrorHandlers errorHandlers) {
        String header = properties.getSecurity().getApiKeyHeader();
        AuthenticationWebFilter filter = new AuthenticationWebFilter(new ApiKeyAuthenticationManager(apiKeyService));
        filter.setRequiresAuthenticationMatcher(exchange ->
                exchange.getRequest().getHeaders().containsKey(header)
                        ? ServerWebExchangeMatcher.MatchResult.match()
                        : ServerWebExchangeMatcher.MatchResult.notMatch());
        filter.setServerAuthenticationConverter(exchange -> Mono
                .justOrEmpty(exchange.getRequest().getHeaders().getFirst(header))
                .map(String::trim)
                .map(ApiKeyAuthenticationToken::unauthenticated));
        filter.setAuthenticationFailureHandler(new ServerAuthenticationEntryPointFailureHandler(errorHandlers.entryPoint()));
        return filter;
    }

    /**
     * Uses the identity provider's JWKS endpoint when configured (key rotation for free),
     * otherwise a pinned RSA public key. Only RS256 is accepted.
     */
    @Bean
    public ReactiveJwtDecoder jwtDecoder(OpenMeshProperties properties, RsaKeyProvider keyProvider) {
        OpenMeshProperties.Jwt jwt = properties.getSecurity().getJwt();
        NimbusReactiveJwtDecoder decoder;
        if (jwt.getJwkSetUri() != null && !jwt.getJwkSetUri().isBlank()) {
            log.info("JWT verification: JWKS endpoint {}", jwt.getJwkSetUri());
            decoder = NimbusReactiveJwtDecoder.withJwkSetUri(jwt.getJwkSetUri())
                    .jwsAlgorithm(SignatureAlgorithm.RS256)
                    .build();
        } else {
            decoder = NimbusReactiveJwtDecoder.withPublicKey(keyProvider.getPublicKey().orElseThrow(() ->
                            new IllegalStateException("No JWT verification key configured. Set openmesh.security.jwt.jwk-set-uri "
                                    + "or openmesh.security.jwt.public-key-location (or run with the 'dev' profile).")))
                    .signatureAlgorithm(SignatureAlgorithm.RS256)
                    .build();
            log.info("JWT verification: pinned RSA public key");
        }

        List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
        validators.add(new JwtTimestampValidator(jwt.getClockSkew()));
        validators.add(new JwtIssuerValidator(jwt.getIssuer()));
        validators.add(token -> token.getExpiresAt() != null
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Token has no expiry (exp)", null)));
        validators.add(token -> token.getSubject() != null && !token.getSubject().isBlank()
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Token has no subject (sub)", null)));
        if (jwt.getAudience() != null && !jwt.getAudience().isBlank()) {
            String audience = jwt.getAudience();
            validators.add(token -> token.getAudience() != null && token.getAudience().contains(audience)
                    ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Token audience mismatch", null)));
        }
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(validators));
        return decoder;
    }

    /** Maps "scope"/"scp" to SCOPE_x and the roles claim to ROLE_x authorities. */
    static Converter<Jwt, Mono<AbstractAuthenticationToken>> jwtAuthenticationConverter(OpenMeshProperties properties) {
        String rolesClaim = properties.getSecurity().getJwt().getRolesClaim();
        return new ReactiveJwtAuthenticationConverterAdapter(jwt -> {
            List<GrantedAuthority> authorities = new ArrayList<>();
            JwtClaims.scopes(jwt).forEach(s -> authorities.add(new SimpleGrantedAuthority("SCOPE_" + s)));
            JwtClaims.roles(jwt, rolesClaim).forEach(r -> authorities.add(new SimpleGrantedAuthority(r)));
            return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
        });
    }
}
