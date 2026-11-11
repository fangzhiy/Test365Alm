package com.test365alm.server.signature;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import com.test365alm.server.project.ProjectAccessException;
import tools.jackson.databind.JsonNode;

/** Pure rules; authentication evidence must come from Spring's verified OIDC token. */
public final class SignatureRules {
    private SignatureRules() { }
    public enum Kind {
        TEST_APPROVAL("用例批准确认", "APPROVED"),
        RUN_COMPLETION("运行完成确认", "FINISHED"),
        DEVIATION_CLOSURE("偏差关闭确认", "CLOSED");
        private final String meaning, state;
        Kind(String meaning, String state) { this.meaning=meaning; this.state=state; }
        public String meaning() { return meaning; }
        public String requiredState() { return state; }
    }
    public static Kind kind(String value) {
        try { return Kind.valueOf(value); }
        catch (IllegalArgumentException | NullPointerException e) { throw ProjectAccessException.invalid("Unknown signature kind"); }
    }
    public static void requireState(Kind kind, String state) {
        if (!kind.requiredState().equals(state))
            throw ProjectAccessException.conflict("SIGNATURE_STATE_CONFLICT", "The exact snapshot is not in a signable state");
    }
    public static void empty(JsonNode body) {
        if (body==null || !body.isObject() || !body.isEmpty()) throw ProjectAccessException.invalid("An empty object is required");
    }
    public static UUID challenge(JsonNode body) {
        if (body==null || !body.isObject() || body.size()!=1 || !body.has("challengeId") || !body.get("challengeId").isTextual())
            throw ProjectAccessException.invalid("Only challengeId is accepted");
        try {
            String text=body.get("challengeId").textValue();
            UUID id=UUID.fromString(text);
            if (!id.toString().equalsIgnoreCase(text)) throw new IllegalArgumentException();
            return id;
        } catch (IllegalArgumentException e) { throw ProjectAccessException.invalid("Invalid challengeId"); }
    }
    public static void reauthenticated(String issuer, String subject, String actualIssuer, String actualSubject,
            Instant authTime, Instant created, Instant now) {
        if (issuer==null || subject==null || !issuer.equals(actualIssuer) || !subject.equals(actualSubject)
                || authTime==null || authTime.isBefore(created.truncatedTo(ChronoUnit.SECONDS))
                || authTime.isAfter(now.plusSeconds(30)) || authTime.isBefore(now.minusSeconds(120))
                || !now.isBefore(created.plusSeconds(300)))
            throw ProjectAccessException.forbidden();
    }
}
