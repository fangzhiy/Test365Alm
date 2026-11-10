package com.test365alm.server.review;

import java.util.Set;
import tools.jackson.databind.JsonNode;
import com.test365alm.server.project.ProjectAccessException;

/** Review decisions belong to one sealed content revision. */
public final class ReviewRules {
    private ReviewRules() { }
    public record Command(String action, String reason) { }

    public static Command command(JsonNode body) {
        if (body == null || !body.isObject()) throw ProjectAccessException.invalid("Review command must be an object");
        for (String name : body.propertyNames())
            if (!Set.of("action", "reason").contains(name)) throw ProjectAccessException.invalid("Unknown review command field");
        JsonNode action = body.get("action"), reason = body.get("reason");
        if (action == null || !action.isTextual() || !Set.of("SUBMIT", "APPROVE", "REJECT").contains(action.textValue()))
            throw ProjectAccessException.invalid("action must be SUBMIT, APPROVE or REJECT");
        if (reason != null && !reason.isTextual()) throw ProjectAccessException.invalid("reason must be text");
        String text = reason == null ? "" : reason.textValue().trim();
        if (text.length() > 2000 || (action.textValue().equals("REJECT") && text.isBlank()))
            throw ProjectAccessException.unprocessable("Rejection requires a reason of at most 2000 characters");
        return new Command(action.textValue(), text);
    }

    public static String next(String state, String action) {
        if (action.equals("SUBMIT") && (state.equals("DRAFT") || state.equals("REJECTED"))) return "SUBMITTED";
        if (state.equals("SUBMITTED") && action.equals("APPROVE")) return "APPROVED";
        if (state.equals("SUBMITTED") && action.equals("REJECT")) return "REJECTED";
        throw ProjectAccessException.conflict("REVIEW_STATE_CONFLICT", "The revision cannot perform this review action");
    }
}
