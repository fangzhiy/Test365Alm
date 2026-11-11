package com.test365alm.server.identity;

import java.io.IOException;
import java.net.URI;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import com.test365alm.server.signature.SignatureLoginFlow;
import com.test365alm.server.project.ProjectAccessResolver;
import com.test365alm.server.project.ProjectService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.InvalidCsrfTokenException;
import org.springframework.security.web.csrf.MissingCsrfTokenException;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.oauth2.core.OAuth2Error;

@Configuration
public class IdentitySecurity {

    @Bean
    @ConditionalOnProperty(name = "test365alm.oidc.enabled", havingValue = "true")
    SecurityFilterChain oidcSecurity(HttpSecurity http, PrincipalRepository principals,
            ClientRegistrationRepository registrations, SignatureLoginFlow signatureLogin,
            ProjectAccessResolver actors, ProjectService projects,
            @Value("${test365alm.web.origin}") String webOrigin,
            @Value("${server.address:127.0.0.1}") String serverAddress,
            @Value("${server.servlet.session.cookie.secure:false}") boolean secureCookie) throws Exception {
        enforceCookieBoundary(serverAddress, webOrigin, secureCookie);
        configureCommon(http);
        http.addFilterBefore(new LoginDiagnostics(), OAuth2AuthorizationRequestRedirectFilter.class);
        OidcUserService delegate = new OidcUserService();
        http.oauth2Login(oauth -> oauth
                .authorizationEndpoint(endpoint -> endpoint.authorizationRequestResolver(signatureLogin.resolver(registrations)))
                .userInfoEndpoint(userInfo -> userInfo.oidcUserService(request -> {
                    var user = delegate.loadUser(request);
                    var token = user.getIdToken();
                    try {
                        principals.upsertVerified(token.getIssuer().toString(), token.getSubject(),
                                user.getFullName() != null ? user.getFullName() : user.getPreferredUsername());
                    } catch (PrincipalRepository.DisabledPrincipalException disabled) {
                        throw new OAuth2AuthenticationException("identity_disabled");
                    } catch (RuntimeException unavailable) {
                        throw new OAuth2AuthenticationException(new OAuth2Error("local_principal_failure"));
                    }
                    return user;
                }))
                .successHandler((request, response, authentication) -> {
                    if(signatureLogin.pending(request)) {
                        try {
                            var pending=signatureLogin.pendingValue(request);
                            var actor=actors.requirePrincipal(authentication);
                            if(!actor.equals(pending.actorId()))throw new OAuth2AuthenticationException("signature_identity_mismatch");
                            projects.requireTestWriteAccess(actor,pending.projectId());
                            var token=((OidcUser)authentication.getPrincipal()).getIdToken();
                            signatureLogin.complete(request,token.getIssuer().toString(),token.getSubject(),token.getAuthenticatedAt());
                            LoginDiagnostics.event(request, response, "signature", "authenticated");
                            response.sendRedirect(webOrigin+"/?signature=reauthenticated");
                        } catch(RuntimeException denied) {
                            LoginDiagnostics.event(request, response, "signature", "binding_or_permission_rejected");
                            request.getSession().invalidate();
                            org.springframework.security.core.context.SecurityContextHolder.clearContext();
                            response.sendRedirect(webOrigin+"/?signature=failed");
                        }
                    } else {
                        LoginDiagnostics.event(request, response, "login", "authenticated");
                        response.sendRedirect(webOrigin+"/");
                    }
                })
                .failureHandler((request, response, exception) -> {
                    LoginDiagnostics.failure(request, response, exception);
                    if(signatureLogin.pending(request)) {
                        request.getSession().invalidate();
                        org.springframework.security.core.context.SecurityContextHolder.clearContext();
                        response.sendRedirect(webOrigin+"/?signature=failed");
                    } else response.sendRedirect(webOrigin+"/?login=failed");
                }));
        return http.build();
    }

    @Bean
    @ConditionalOnProperty(name = "test365alm.oidc.enabled", havingValue = "false", matchIfMissing = true)
    SecurityFilterChain platformOnlySecurity(HttpSecurity http) throws Exception {
        configureCommon(http);
        return http.build();
    }

    private void configureCommon(HttpSecurity http) throws Exception {
        // SPA login has a fixed success target. Saving anonymous API requests creates
        // competing JSESSIONID cookies; a late 401 can discard the authorization session.
        // OAuth2's authorization request repository still uses its normal session.
        http.requestCache(cache -> cache.requestCache(new NullRequestCache()));
        http.authorizeHttpRequests(auth -> auth
                .requestMatchers("/health/live", "/health/ready", "/api/v1/version", "/api/v1/csrf",
                        "/oauth2/authorization/test365alm", "/login/oauth2/code/test365alm").permitAll()
                .requestMatchers("/api/v1/me", "/api/v1/auth/logout", "/api/v1/tenants", "/api/v1/domains",
                        "/api/v1/projects/**", "/api/v1/me/permissions").authenticated()
                .anyRequest().denyAll());
        http.exceptionHandling(errors -> errors
                .authenticationEntryPoint((request, response, exception) -> json(response, 401, "UNAUTHENTICATED"))
                .accessDeniedHandler((request, response, exception) -> json(response, 403,
                        exception instanceof InvalidCsrfTokenException || exception instanceof MissingCsrfTokenException
                                ? "CSRF_REJECTED" : "FORBIDDEN")));
        http.logout(logout -> logout.logoutUrl("/api/v1/auth/logout")
                .logoutSuccessHandler((request, response, authentication) -> json(response, 200, "LOGGED_OUT"))
                .invalidateHttpSession(true).clearAuthentication(true).deleteCookies("JSESSIONID"));
    }

    private static void json(HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(status == 200 ? "{\"status\":\"" + code + "\"}" : "{\"code\":\"" + code + "\"}");
    }

    static void enforceCookieBoundary(String serverAddress, String webOrigin, boolean secureCookie) {
        URI origin = URI.create(webOrigin);
        if (origin.getHost() == null || origin.getRawUserInfo() != null || origin.getRawQuery() != null
                || origin.getRawFragment() != null || !(origin.getRawPath() == null || origin.getRawPath().isEmpty())) {
            throw new IllegalStateException("Browser origin must be an exact origin without credentials or path");
        }
        if (secureCookie) {
            if (!"https".equals(origin.getScheme())) {
                throw new IllegalStateException("Secure session cookie requires an HTTPS browser origin");
            }
        } else if (!(serverAddress.equals("127.0.0.1") || serverAddress.equals("::1"))
                || !"http".equals(origin.getScheme()) || !"127.0.0.1".equals(origin.getHost())
                || origin.getPort() <= 0) {
            throw new IllegalStateException("HTTP session cookies are allowed only on the loopback development origin");
        }
    }
}
