package com.test365alm.server.identity;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class IdentityController {
    private final PrincipalRepository principals;

    public IdentityController(PrincipalRepository principals) {
        this.principals = principals;
    }

    @GetMapping("/api/v1/csrf")
    public Map<String, String> csrf(CsrfToken token) {
        return Map.of("headerName", token.getHeaderName(), "token", token.getToken());
    }

    @GetMapping("/api/v1/me")
    public ResponseEntity<?> me(Authentication authentication, HttpServletRequest request) {
        if (authentication == null || !(authentication.getPrincipal() instanceof OidcUser user)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("code", "UNAUTHENTICATED"));
        }
        var idToken = user.getIdToken();
        var principal = principals.find(idToken.getIssuer().toString(), idToken.getSubject());
        if (principal == null || principal.disabled()) {
            if (request.getSession(false) != null) request.getSession(false).invalidate();
            SecurityContextHolder.clearContext();
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("code", "IDENTITY_DISABLED"));
        }
        return ResponseEntity.ok(Map.of(
                "id", principal.id().toString(), "issuer", principal.issuer(),
                "subject", principal.subject(), "displayName", principal.displayName()));
    }
}
