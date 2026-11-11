package com.test365alm.server.identity;

import java.io.IOException;
import java.util.UUID;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.web.filter.OncePerRequestFilter;

/** Deliberately contains no session ID, protocol parameter, identity or exception text. */
final class LoginDiagnostics extends OncePerRequestFilter {
    private static final String KEY = LoginDiagnostics.class.getName();
    private record Attempt(String id, long started) { }
    record Failure(String stage, String category) { }

    static Failure classify(Exception error) {
        String code = error instanceof OAuth2AuthenticationException oauth ? oauth.getError().getErrorCode() : "";
        return switch (code) {
            case "authorization_request_not_found" -> new Failure("session_association", "request_missing");
            case "invalid_state_parameter" -> new Failure("oauth_validation", "state_rejected");
            case "invalid_nonce", "invalid_id_token" -> new Failure("token_validation", "token_rejected");
            case "invalid_token_response" -> new Failure("token_exchange", "exchange_rejected");
            case "invalid_user_info_response" -> new Failure("user_info", "profile_rejected");
            case "identity_disabled" -> new Failure("local_principal", "identity_disabled");
            case "local_principal_failure" -> new Failure("local_principal", "persistence_failed");
            case "access_denied" -> new Failure("authorization", "access_denied");
            default -> new Failure("authentication", "unclassified_failure");
        };
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getServletPath().equals("/oauth2/authorization/test365alm")
                && !request.getServletPath().equals("/login/oauth2/code/test365alm");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        boolean start = request.getServletPath().equals("/oauth2/authorization/test365alm");
        var session = request.getSession(false);
        Attempt attempt = !start && session != null && session.getAttribute(KEY) instanceof Attempt saved
                ? saved : new Attempt(UUID.randomUUID().toString(), System.nanoTime());
        request.setAttribute(KEY, attempt);
        response.setHeader("X-Auth-Diagnostic-Id", attempt.id());
        if (!start) event(request, response, "callback", session == null ? "no_session" : "session_present");
        try {
            chain.doFilter(request, response);
        } finally {
            if (start) {
                session = request.getSession(false);
                if (session != null) session.setAttribute(KEY, attempt);
                event(request, response, "authorization", "redirected");
            }
        }
    }

    static void event(HttpServletRequest request, HttpServletResponse response, String stage, String category) {
        if (!(request.getAttribute(KEY) instanceof Attempt attempt)) return;
        response.setHeader("X-Auth-Stage", stage);
        response.setHeader("X-Auth-Category", category);
        LoggerFactory.getLogger(LoginDiagnostics.class).info("OIDC diagnostic id={} stage={} category={} elapsed_ms={}",
                attempt.id(), stage, category, Math.max(0, (System.nanoTime() - attempt.started()) / 1_000_000));
    }

    static void failure(HttpServletRequest request, HttpServletResponse response, Exception error) {
        var failure = classify(error);
        event(request, response, failure.stage(), failure.category());
    }
}
