package com.test365alm.server.signature;

import java.time.Instant;
import java.util.UUID;

/** Replacement boundary. No passwords, OAuth tokens or proof secrets may cross it. */
public interface SignatureProvider {
    Evidence sign(Material material);
    record Material(UUID signatureId, UUID projectId, SignatureRules.Kind kind, UUID objectId, UUID snapshotId,
            String contentDigest, UUID actorId, String issuer, String subject, String displayName,
            Instant authenticatedAt, Instant signedAt) { }
    record Evidence(String provider, boolean simulated, String reference, String bindingDigest) { }
}
