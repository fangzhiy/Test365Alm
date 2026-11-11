package com.test365alm.server.identity;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;

class LoginDiagnosticsTest {
    @Test
    void classifiesKnownStagesAndNeverReflectsUnknownErrorOrMessage() {
        assertEquals("session_association", LoginDiagnostics.classify(new OAuth2AuthenticationException("authorization_request_not_found")).stage());
        assertEquals("token_validation", LoginDiagnostics.classify(new OAuth2AuthenticationException("invalid_id_token")).stage());
        assertEquals("token_exchange", LoginDiagnostics.classify(new OAuth2AuthenticationException("invalid_token_response")).stage());
        assertEquals("local_principal", LoginDiagnostics.classify(new OAuth2AuthenticationException("identity_disabled")).stage());
        var unknown = new OAuth2AuthenticationException(new OAuth2Error("secret-code-state"), "secret-token-cookie");
        assertEquals(new LoginDiagnostics.Failure("authentication", "unclassified_failure"), LoginDiagnostics.classify(unknown));
    }

    @Test
    void linksAuthorizationAndCallbackWithIndependentRandomDiagnosticId() throws Exception {
        var filter = new LoginDiagnostics();
        var start = new MockHttpServletRequest();
        start.setServletPath("/oauth2/authorization/test365alm");
        var redirect = new MockHttpServletResponse();
        filter.doFilter(start, redirect, (r, s) -> start.getSession());
        String id = redirect.getHeader("X-Auth-Diagnostic-Id");
        assertTrue(id.matches("[0-9a-f-]{36}"));
        assertNotEquals(start.getSession().getId(), id);
        var callback = new MockHttpServletRequest();
        callback.setServletPath("/login/oauth2/code/test365alm");
        callback.setSession((org.springframework.mock.web.MockHttpSession) start.getSession());
        var response = new MockHttpServletResponse();
        filter.doFilter(callback, response, (r, s) -> LoginDiagnostics.failure(callback, response,
                new OAuth2AuthenticationException(new OAuth2Error("invalid_nonce"), "must-not-appear")));
        assertEquals(id, response.getHeader("X-Auth-Diagnostic-Id"));
        assertEquals("token_rejected", response.getHeader("X-Auth-Category"));
        assertEquals("token_validation", response.getHeader("X-Auth-Stage"));
        assertFalse(response.getContentAsString().contains("must-not-appear"));
    }

    @Test
    void missingSessionDiagnosticDoesNotCreateOne() throws Exception {
        var request = new MockHttpServletRequest();
        request.setServletPath("/login/oauth2/code/test365alm");
        var response = new MockHttpServletResponse();
        new LoginDiagnostics().doFilter(request, response, (r, s) -> { });
        assertNull(request.getSession(false));
        assertEquals("no_session", response.getHeader("X-Auth-Category"));
    }
}
