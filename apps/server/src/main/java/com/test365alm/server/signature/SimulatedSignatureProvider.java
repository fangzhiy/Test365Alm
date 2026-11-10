package com.test365alm.server.signature;

import com.test365alm.server.core.CoreCommands;
import org.springframework.stereotype.Component;

/** Demonstration evidence only. The digest is not a vendor signature or a certificate. */
@Component
public final class SimulatedSignatureProvider implements SignatureProvider {
    @Override public Evidence sign(Material m) {
        String binding=CoreCommands.hash("SIMULATED_SIGNATURE_V1",m.signatureId().toString(),m.projectId().toString(),
            m.kind().name(),m.kind().meaning(),m.objectId().toString(),m.snapshotId().toString(),m.contentDigest(),
            m.actorId().toString(),m.issuer(),m.subject(),m.displayName(),m.authenticatedAt().toString(),m.signedAt().toString());
        return new Evidence("SIMULATED_V1",true,m.signatureId().toString(),binding);
    }
}
