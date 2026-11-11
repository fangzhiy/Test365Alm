package com.test365alm.server.signature;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import com.test365alm.server.core.CoreCommands;
import com.test365alm.server.project.ProjectAccessException;

class SignatureRulesTest {
    @Test void onlyThreeServerDefinedMeaningsAndTerminalStatesAreSignable() {
        assertEquals("FINISHED", SignatureRules.kind("RUN_COMPLETION").requiredState());
        for (String kind : new String[]{"TEST_APPROVAL", "RUN_COMPLETION", "DEVIATION_CLOSURE"}) {
            var target = SignatureRules.kind(kind);
            assertFalse(target.meaning().isBlank());
            SignatureRules.requireState(target, target.requiredState());
            for (String state : new String[]{"DRAFT", "SUBMITTED", "RUNNING", "PAUSED", "RESOLVED"})
                assertThrows(ProjectAccessException.class, () -> SignatureRules.requireState(target, state));
        }
        for (String kind : new String[]{"test_approval", "APPROVE", "", "REAL_VENDOR"})
            assertThrows(ProjectAccessException.class, () -> SignatureRules.kind(kind));
    }

    @Test void commandsRejectIdentityCredentialsDigestsAndExtraChallengeFields() {
        SignatureRules.empty(CoreCommands.JSON.readTree("{}"));
        for (String body : new String[]{"null", "[]", "{\"name\":\"me\"}", "{\"password\":\"x\"}",
                "{\"token\":\"x\"}", "{\"meaning\":\"approve\"}", "{\"contentDigest\":\"x\"}"})
            assertThrows(ProjectAccessException.class, () -> SignatureRules.empty(CoreCommands.JSON.readTree(body)));
        var id = java.util.UUID.randomUUID();
        assertEquals(id, SignatureRules.challenge(CoreCommands.JSON.readTree("{\"challengeId\":\""+id+"\"}")));
        for (String body : new String[]{"{}", "{\"challengeId\":null}", "{\"challengeId\":7}",
                "{\"challengeId\":\"forged\"}", "{\"challengeId\":\""+id+"\",\"actorId\":\"x\"}"})
            assertThrows(ProjectAccessException.class, () -> SignatureRules.challenge(CoreCommands.JSON.readTree(body)));
    }

    @Test void reauthenticationRequiresSameIssuerSubjectAndFreshVerifiedAuthenticationTime() {
        var created = Instant.parse("2026-11-11T12:00:00.900Z");
        var now = created.plusSeconds(60);
        SignatureRules.reauthenticated("https://idp", "subject", "https://idp", "subject", created.minusNanos(900_000_000), created, now);
        assertThrows(ProjectAccessException.class, () -> SignatureRules.reauthenticated("a","s","b","s",created,created,now));
        assertThrows(ProjectAccessException.class, () -> SignatureRules.reauthenticated("a","s","a","other",created,created,now));
        for (Instant authTime : new Instant[]{null, created.minusSeconds(2), now.plusSeconds(31), now.minusSeconds(121)})
            assertThrows(ProjectAccessException.class, () -> SignatureRules.reauthenticated("a","s","a","s",authTime,created,now));
        assertThrows(ProjectAccessException.class, () -> SignatureRules.reauthenticated("a","s","a","s",now,created,created.plusSeconds(301)));
    }
}
