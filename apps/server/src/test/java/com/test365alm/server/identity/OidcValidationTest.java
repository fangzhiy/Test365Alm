package com.test365alm.server.identity;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.Set;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.authentication.OAuth2LoginAuthenticationToken;
import org.springframework.security.oauth2.client.oidc.authentication.OidcAuthorizationCodeAuthenticationProvider;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationExchange;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationResponse;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;

class OidcValidationTest {
    private static final String NONCE = "test-nonce-only";
    private static final String REDIRECT = "http://127.0.0.1:5173/login/oauth2/code/test365alm";
    private static HttpServer jwksServer;
    private static RSAKey signingKey;
    private static ClientRegistration registration;

    @BeforeAll
    static void startJwks() throws Exception {
        signingKey = new RSAKeyGenerator(2048).keyID("r03-fixture").generate();
        jwksServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        jwksServer.createContext("/jwks", exchange -> {
            byte[] body = ("{\"keys\":[" + signingKey.toPublicJWK().toJSONString() + "]}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        jwksServer.start();
        String issuer = "http://127.0.0.1:" + jwksServer.getAddress().getPort() + "/issuer";
        registration = ClientRegistration.withRegistrationId("test365alm")
                .clientId("test365alm-web")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(REDIRECT)
                .authorizationUri(issuer + "/auth")
                .tokenUri(issuer + "/token")
                .jwkSetUri("http://127.0.0.1:" + jwksServer.getAddress().getPort() + "/jwks")
                .issuerUri(issuer)
                .scope("openid")
                .build();
    }

    @AfterAll
    static void stopJwks() {
        jwksServer.stop(0);
    }

    @Test
    void validSignedIdTokenPassesActualOidcProvider() throws Exception {
        assertNotNull(authenticate(token(null, null, null, null, null)));
    }

    @Test
    void wrongSignatureIsRejectedByActualOidcProvider() throws Exception {
        RSAKey other = new RSAKeyGenerator(2048).keyID("r03-fixture").generate();
        assertRejected("invalid_id_token", token(null, null, null, null, other));
    }

    @Test
    void wrongIssuerIsRejectedByActualOidcProvider() throws Exception {
        assertRejected("invalid_id_token", token("http://example.invalid/issuer", null, null, null, null));
    }

    @Test
    void wrongAudienceIsRejectedByActualOidcProvider() throws Exception {
        assertRejected("invalid_id_token", token(null, "other-client", null, null, null));
    }

    @Test
    void expiredIdTokenIsRejectedByActualOidcProvider() throws Exception {
        assertRejected("invalid_id_token", token(null, null, Instant.now().minusSeconds(600), null, null));
    }

    @Test
    void wrongNonceIsRejectedByActualOidcProvider() throws Exception {
        assertRejected("invalid_nonce", token(null, null, null, "wrong-nonce", null));
    }

    private static void assertRejected(String code, String idToken) {
        var rejected = assertThrows(OAuth2AuthenticationException.class, () -> authenticate(idToken));
        assertEquals(code, rejected.getError().getErrorCode());
    }

    private static Object authenticate(String idToken) {
        OAuth2AuthorizationRequest request = OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri(registration.getProviderDetails().getAuthorizationUri())
                .clientId(registration.getClientId())
                .redirectUri(REDIRECT)
                .scopes(Set.of("openid"))
                .state("valid-state")
                .attributes(attributes -> attributes.put("nonce", NONCE))
                .build();
        OAuth2AuthorizationResponse response = OAuth2AuthorizationResponse.success("valid-code")
                .redirectUri(REDIRECT).state("valid-state").build();
        var exchange = new OAuth2AuthorizationExchange(request, response);
        var provider = new OidcAuthorizationCodeAuthenticationProvider(
                grant -> OAuth2AccessTokenResponse.withToken("fixture-access-token")
                        .tokenType(OAuth2AccessToken.TokenType.BEARER)
                        .expiresIn(300)
                        .additionalParameters(Map.of("id_token", idToken))
                        .build(), new OidcUserService());
        return provider.authenticate(new OAuth2LoginAuthenticationToken(registration, exchange));
    }

    private static String token(String issuer, String audience, Instant expiry,
            String nonce, RSAKey key) throws Exception {
        Instant now = Instant.now();
        String expectedNonce = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(NONCE.getBytes(StandardCharsets.UTF_8)));
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer == null ? registration.getProviderDetails().getIssuerUri() : issuer)
                .subject("r03-fixture-subject")
                .audience(audience == null ? registration.getClientId() : audience)
                .issueTime(Date.from(now.minusSeconds(5)))
                .expirationTime(Date.from(expiry == null ? now.plusSeconds(300) : expiry))
                .claim("nonce", nonce == null ? expectedNonce : nonce)
                .build();
        SignedJWT signed = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256)
                .keyID(signingKey.getKeyID()).build(), claims);
        signed.sign(new RSASSASigner(key == null ? signingKey : key));
        return signed.serialize();
    }
}
