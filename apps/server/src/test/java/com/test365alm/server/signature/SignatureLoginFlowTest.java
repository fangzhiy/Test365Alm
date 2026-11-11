package com.test365alm.server.signature;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

class SignatureLoginFlowTest {
    @Test void onlyServerPreparedSessionChallengesForceFreshOidcAuthorizationAndBindState() {
        var flow=new SignatureLoginFlow();var request=request();
        var resolver=flow.resolver(new InMemoryClientRegistrationRepository(registration()));
        var normal=resolver.resolve(request,"test365alm");
        assertFalse(normal.getAdditionalParameters().containsKey("max_age"));
        var pending=pending();flow.begin(request.getSession(),pending);
        var auth=resolver.resolve(request,"test365alm");
        assertEquals("login",auth.getAdditionalParameters().get("prompt"));
        assertEquals(0,auth.getAdditionalParameters().get("max_age"));
        assertNotNull(auth.getAdditionalParameters().get("nonce"));
        assertNotNull(auth.getAdditionalParameters().get("code_challenge"));
        request.setParameter("state",auth.getState());
        var proof=flow.complete(request,"https://idp","subject",Instant.now());
        assertEquals(pending.challengeId(),proof.challengeId());
        assertEquals(proof,flow.proof(request.getSession(),pending.challengeId()));
        assertNull(flow.proof(new MockHttpSession(),pending.challengeId()));
        assertFalse(flow.pending(request));
    }
    @Test void wrongSubjectStateOldTimeAndCrossSessionCannotCreateProof() {
        for(String failure:new String[]{"subject","state","old-time","cross-session"}) {
            var flow=new SignatureLoginFlow();var request=request();var pending=pending();
            flow.begin(request.getSession(),pending);
            var auth=flow.resolver(new InMemoryClientRegistrationRepository(registration())).resolve(request,"test365alm");
            request.setParameter("state",failure.equals("state")?"forged":auth.getState());
            if(failure.equals("cross-session"))request.setSession(new MockHttpSession());
            assertThrows(RuntimeException.class,()->flow.complete(request,"https://idp",failure.equals("subject")?"other":"subject",
                failure.equals("old-time")?Instant.now().minusSeconds(600):Instant.now()),failure);
            assertNull(flow.proof(request.getSession(),pending.challengeId()));
        }
    }
    @Test void stableSecretSessionBindingSurvivesSessionIdRotationAndDiffersAcrossSessions() {
        var flow=new SignatureLoginFlow();var session=new MockHttpSession();
        String binding=flow.binding(session);session.changeSessionId();
        assertEquals(binding,flow.binding(session));assertNotEquals(binding,flow.binding(new MockHttpSession()));
        assertTrue(binding.matches("[a-f0-9]{64}"));
    }
    private static MockHttpServletRequest request(){var r=new MockHttpServletRequest();r.setSession(new MockHttpSession());return r;}
    private static SignatureLoginFlow.Pending pending(){return new SignatureLoginFlow.Pending(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"https://idp","subject",Instant.now(),null);}
    private static ClientRegistration registration(){return ClientRegistration.withRegistrationId("test365alm").clientId("web")
        .clientAuthenticationMethod(ClientAuthenticationMethod.NONE).authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
        .redirectUri("http://127.0.0.1/login/oauth2/code/test365alm").scope("openid","profile")
        .authorizationUri("https://idp/authorize").tokenUri("https://idp/token").jwkSetUri("https://idp/jwks")
        .userInfoUri("https://idp/userinfo").userNameAttributeName("sub").build();}
}
