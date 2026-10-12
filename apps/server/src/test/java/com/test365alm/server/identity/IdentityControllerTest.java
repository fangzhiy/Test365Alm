package com.test365alm.server.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

class IdentityControllerTest {
    @Test
    void disabledLocalIdentityInvalidatesExistingSession() {
        var repository = mock(PrincipalRepository.class);
        var controller = new IdentityController(repository);
        var request = new MockHttpServletRequest();
        var session = (MockHttpSession) request.getSession(true);
        var authentication = mock(Authentication.class);
        var user = mock(OidcUser.class);
        var token = new OidcIdToken("synthetic-only", Instant.now(), Instant.now().plusSeconds(60),
                Map.of("iss", "https://issuer.example", "sub", "sub-1"));
        when(authentication.getPrincipal()).thenReturn(user);
        when(user.getIdToken()).thenReturn(token);
        when(repository.find("https://issuer.example", "sub-1"))
                .thenReturn(new PrincipalRepository.Principal(UUID.randomUUID(), "https://issuer.example",
                        "sub-1", "Disabled", true));

        var response = controller.me(authentication, request);
        assertEquals(403, response.getStatusCode().value());
        assertTrue(session.isInvalid());
    }
}
