package com.test365alm.server.signature;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SimulatedSignatureProviderTest {
    @Test void evidenceIsExplicitlySimulatedAndBindsEveryServerVerifiedMaterialField() {
        var provider = new SimulatedSignatureProvider();
        var a = material("subject", "digest", Instant.parse("2026-11-11T12:00:00Z"));
        var evidence = provider.sign(a);
        assertEquals("SIMULATED_V1", evidence.provider());
        assertTrue(evidence.simulated());
        assertEquals(a.signatureId().toString(), evidence.reference());
        assertTrue(evidence.bindingDigest().matches("[a-f0-9]{64}"));
        assertEquals(evidence, provider.sign(a));
        for (var other : new SignatureProvider.Material[]{
                new SignatureProvider.Material(a.signatureId(), a.projectId(), a.kind(), a.objectId(), a.snapshotId(),
                    a.contentDigest(), a.actorId(), a.issuer(), "another-subject", a.displayName(), a.authenticatedAt(), a.signedAt()),
                new SignatureProvider.Material(a.signatureId(), a.projectId(), a.kind(), a.objectId(), UUID.randomUUID(),
                    a.contentDigest(), a.actorId(), a.issuer(), a.subject(), a.displayName(), a.authenticatedAt(), a.signedAt()),
                new SignatureProvider.Material(a.signatureId(), a.projectId(), a.kind(), a.objectId(), a.snapshotId(),
                    "changed", a.actorId(), a.issuer(), a.subject(), a.displayName(), a.authenticatedAt(), a.signedAt())})
            assertNotEquals(evidence.bindingDigest(), provider.sign(other).bindingDigest());
    }
    private static SignatureProvider.Material material(String subject, String digest, Instant time) {
        return new SignatureProvider.Material(UUID.randomUUID(),UUID.randomUUID(),SignatureRules.Kind.TEST_APPROVAL,
            UUID.randomUUID(),UUID.randomUUID(),digest,UUID.randomUUID(),"https://verified-idp",subject,"Frozen name",time.minusSeconds(10),time);
    }
}
