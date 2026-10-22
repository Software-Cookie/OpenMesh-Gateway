package io.openmesh.gateway.security;

import io.openmesh.gateway.model.ApiKeyRecord;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Authentication for a caller that presented a valid API key. */
public class ApiKeyAuthenticationToken extends AbstractAuthenticationToken {

    private final transient String presentedKey;
    private final transient ApiKeyRecord record;

    private ApiKeyAuthenticationToken(String presentedKey) {
        super(List.of());
        this.presentedKey = presentedKey;
        this.record = null;
        setAuthenticated(false);
    }

    private ApiKeyAuthenticationToken(ApiKeyRecord record, Collection<? extends GrantedAuthority> authorities) {
        super(authorities);
        this.presentedKey = null;
        this.record = record;
        setAuthenticated(true);
    }

    public static ApiKeyAuthenticationToken unauthenticated(String presentedKey) {
        return new ApiKeyAuthenticationToken(presentedKey);
    }

    /** API keys get ROLE_SERVICE plus their scopes; never ROLE_ADMIN. */
    public static ApiKeyAuthenticationToken authenticated(ApiKeyRecord record) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_SERVICE"));
        record.getScopes().forEach(s -> authorities.add(new SimpleGrantedAuthority("SCOPE_" + s)));
        return new ApiKeyAuthenticationToken(record, authorities);
    }

    public ApiKeyRecord getRecord() {
        return record;
    }

    @Override
    public Object getCredentials() {
        return presentedKey;
    }

    @Override
    public Object getPrincipal() {
        return record != null ? record.getId() : null;
    }

    @Override
    public String getName() {
        return record != null ? "apikey:" + record.getId() : "apikey:unauthenticated";
    }
}
