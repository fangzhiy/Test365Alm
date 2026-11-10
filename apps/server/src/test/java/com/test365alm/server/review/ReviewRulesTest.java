package com.test365alm.server.review;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import com.test365alm.server.core.CoreCommands;
import com.test365alm.server.project.ProjectAccessException;

class ReviewRulesTest {
    @Test void onlySubmittedContentCanBeDecidedAndApprovedContentCannotBeResubmitted() {
        assertEquals("SUBMITTED", ReviewRules.next("DRAFT", "SUBMIT"));
        assertEquals("SUBMITTED", ReviewRules.next("REJECTED", "SUBMIT"));
        assertEquals("APPROVED", ReviewRules.next("SUBMITTED", "APPROVE"));
        assertEquals("REJECTED", ReviewRules.next("SUBMITTED", "REJECT"));
        for (String state : new String[]{"DRAFT", "REJECTED", "APPROVED"}) {
            for (String action : new String[]{"APPROVE", "REJECT"}) {
                assertEquals("REVIEW_STATE_CONFLICT", assertThrows(ProjectAccessException.class,
                    () -> ReviewRules.next(state, action)).code());
            }
        }
        for (String state : new String[]{"SUBMITTED", "APPROVED"})
            assertThrows(ProjectAccessException.class, () -> ReviewRules.next(state, "SUBMIT"));
    }

    @Test void strictCommandRejectsForgedIdentityUnknownActionsAndInvalidReasons() {
        assertEquals("SUBMIT", ReviewRules.command(CoreCommands.JSON.readTree("{\"action\":\"SUBMIT\"}")).action());
        for (String body : new String[]{"null", "[]", "{}", "{\"action\":\"approve\"}",
                "{\"action\":\"APPROVE\",\"actorId\":\"forged\"}",
                "{\"action\":\"APPROVE\",\"reviewerId\":\"forged\"}",
                "{\"action\":\"APPROVE\",\"digest\":\"forged\"}",
                "{\"action\":\"REJECT\",\"reason\":\"  \"}",
                "{\"action\":\"APPROVE\",\"reason\":7}"})
            assertThrows(ProjectAccessException.class, () -> ReviewRules.command(CoreCommands.JSON.readTree(body)), body);
        assertThrows(ProjectAccessException.class, () -> ReviewRules.command(CoreCommands.JSON.createObjectNode()
            .put("action", "REJECT").put("reason", "x".repeat(2001))));
        assertEquals("please revise", ReviewRules.command(CoreCommands.JSON.readTree(
            "{\"action\":\"REJECT\",\"reason\":\" please revise \"}")).reason());
    }
}
