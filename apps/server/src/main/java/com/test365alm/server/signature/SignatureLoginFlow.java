package com.test365alm.server.signature;

import java.time.Instant;
import java.util.HashMap;
import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.stereotype.Component;
import com.test365alm.server.core.CoreCommands;
import com.test365alm.server.project.ProjectAccessException;

/** Server session state only. A browser challenge id or postMessage can never produce a proof. */
@Component
public final class SignatureLoginFlow {
    private static final String BINDING=SignatureLoginFlow.class.getName()+".binding";
    private static final String PENDING=SignatureLoginFlow.class.getName()+".pending";
    private static final String PROOF=SignatureLoginFlow.class.getName()+".proof";
    public record Pending(UUID challengeId,UUID projectId,UUID actorId,String issuer,String subject,Instant createdAt,String state) { }
    public record Proof(UUID challengeId,UUID actorId,String issuer,String subject,Instant authenticatedAt) { }

    public String binding(HttpSession session) {
        synchronized(session) {
            String value=(String)session.getAttribute(BINDING);
            if(value==null) {value=CoreCommands.hash(UUID.randomUUID().toString(),UUID.randomUUID().toString());session.setAttribute(BINDING,value);}
            return value;
        }
    }
    public void begin(HttpSession session,Pending pending) {
        if(pending.state()!=null || !Instant.now().isBefore(pending.createdAt().plusSeconds(300))) throw ProjectAccessException.forbidden();
        session.removeAttribute(PROOF);session.setAttribute(PENDING,pending);
    }
    public boolean pending(HttpServletRequest request) {
        var session=request.getSession(false);return session!=null && session.getAttribute(PENDING) instanceof Pending;
    }
    public Pending pendingValue(HttpServletRequest request) {
        if(!pending(request))throw ProjectAccessException.forbidden();
        return (Pending)request.getSession(false).getAttribute(PENDING);
    }
    public Proof proof(HttpSession session,UUID challengeId) {
        Object value=session.getAttribute(PROOF);
        return value instanceof Proof p && p.challengeId().equals(challengeId)?p:null;
    }
    /** Called exclusively by the existing OIDC success handler after Spring validates the ID token. */
    public Proof complete(HttpServletRequest request,String issuer,String subject,Instant authenticatedAt) {
        var p=pendingValue(request);
        if(p.state()==null || !p.state().equals(request.getParameter("state")))throw ProjectAccessException.forbidden();
        SignatureRules.reauthenticated(p.issuer(),p.subject(),issuer,subject,authenticatedAt,p.createdAt(),Instant.now());
        var proof=new Proof(p.challengeId(),p.actorId(),issuer,subject,authenticatedAt);
        var session=request.getSession(false);session.setAttribute(PROOF,proof);session.removeAttribute(PENDING);
        return proof;
    }
    public OAuth2AuthorizationRequestResolver resolver(ClientRegistrationRepository registrations) {
        var delegate=new DefaultOAuth2AuthorizationRequestResolver(registrations,"/oauth2/authorization");
        return new OAuth2AuthorizationRequestResolver() {
            public OAuth2AuthorizationRequest resolve(HttpServletRequest r) {return customize(r,delegate.resolve(r));}
            public OAuth2AuthorizationRequest resolve(HttpServletRequest r,String id) {return customize(r,delegate.resolve(r,id));}
        };
    }
    private OAuth2AuthorizationRequest customize(HttpServletRequest r,OAuth2AuthorizationRequest original) {
        if(original==null || !pending(r))return original;
        var p=pendingValue(r);
        if(!Instant.now().isBefore(p.createdAt().plusSeconds(300)))throw ProjectAccessException.forbidden();
        var parameters=new HashMap<>(original.getAdditionalParameters());parameters.put("prompt","login");parameters.put("max_age",0);
        r.getSession(false).setAttribute(PENDING,new Pending(p.challengeId(),p.projectId(),p.actorId(),p.issuer(),p.subject(),p.createdAt(),original.getState()));
        return OAuth2AuthorizationRequest.from(original).additionalParameters(parameters).build();
    }
}
