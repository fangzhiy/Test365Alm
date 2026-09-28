package com.test365alm.server.identity;

import java.io.IOException;
import java.net.URI;

import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.InvalidCsrfTokenException;
import org.springframework.security.web.csrf.MissingCsrfTokenException;

@Configuration
public class IdentitySecurity {
    private static final Logger log = LoggerFactory.getLogger(IdentitySecurity.class);

    @Bean
    @ConditionalOnProperty(name = "test365alm.oidc.enabled", havingValue = "true")
    SecurityFilterChain oidcSecurity(HttpSecurity http, PrincipalRepository principals,
            @Value("${test365alm.web.origin}") String webOrigin,
            @Value("${server.address:127.0.0.1}") String serverAddress,
            @Value("${server.servlet.session.cookie.secure:false}") boolean secureCookie) throws Exception {
        enforceCookieBoundary(serverAddress, webOrigin, secureCookie);
        configureCommon(http);
        OidcUserService delegate = new OidcUserService();
        http.oauth2Login(oauth -> oauth
                .userInfoEndpoint(userInfo -> userInfo.oidcUserService(request -> {
                    var user = delegate.loadUser(request);
                    var token = user.getIdToken();
                    try {
                        var local = principals.upsertVerified(token.getIssuer().toString(), token.getSubject(),
                                user.getFullName() != null ? user.getFullName() : user.getPreferredUsername());
                        log.info("OIDC login resolved local principal {}", local.id());
                    } catch (PrincipalRepository.DisabledPrincipalException disabled) {
                        log.warn("OIDC login refused: local principal disabled");
                        throw new OAuth2AuthenticationException("identity_disabled");
                    }
                    return user;
                }))
                .successHandler((request, response, authentication) -> response.sendRedirect(webOrigin + "/"))
                .failureHandler((request, response, exception) -> response.sendRedirect(webOrigin + "/?login=failed")));
        return http.build();
    }

    @Bean
    @ConditionalOnProperty(name = "test365alm.oidc.enabled", havingValue = "false", matchIfMissing = true)
    SecurityFilterChain platformOnlySecurity(HttpSecurity http) throws Exception {
        configureCommon(http);
        return http.build();
    }

    private void configureCommon(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(auth -> auth
                .requestMatchers("/health/live", "/health/ready", "/api/v1/version", "/api/v1/csrf",
                        "/oauth2/authorization/test365alm", "/login/oauth2/code/test365alm").permitAll()
                .requestMatchers("/api/v1/me", "/api/v1/auth/logout").authenticated()
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
