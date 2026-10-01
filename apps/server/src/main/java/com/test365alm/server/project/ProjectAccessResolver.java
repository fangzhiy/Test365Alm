package com.test365alm.server.project;

import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;

import com.test365alm.server.identity.PrincipalRepository;

/** Resolves only the server-created principal for a verified OIDC session. */
@Component
public class ProjectAccessResolver {
    private final PrincipalRepository principals;

    public ProjectAccessResolver(PrincipalRepository principals) {
        this.principals = principals;
    }

    public UUID requirePrincipal(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof OidcUser user)
                || user.getIdToken() == null
                || user.getIdToken().getIssuer() == null
                || user.getIdToken().getSubject() == null) {
            throw ProjectAccessException.unauthenticated();
        }
        var local = principals.find(user.getIdToken().getIssuer().toString(), user.getIdToken().getSubject());
        if (local == null || local.disabled()) throw ProjectAccessException.forbidden();
        return local.id();
    }
}
