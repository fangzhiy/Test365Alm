package com.test365alm.server.requirement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import com.test365alm.server.project.ProjectAccessException;

class RequirementServiceTest {
    @Test
    void ifMatchAcceptsOnlyOneStrongQuotedPositiveTag() {
        assertEquals(1L, RequirementService.parseIfMatch("\"1\""));
        assertEquals(42L, RequirementService.parseIfMatch("\"42\""));
    }

    @Test
    void ifMatchRejectsBareWeakWildcardAndMalformedValues() {
        assertInvalid("1");
        assertInvalid("W/\"1\"");
        assertPreconditionRequired("*");
        assertInvalid("\"0\"");
        assertInvalid("\"1");
        assertInvalid("1\"");
        assertInvalid("\"1\"\"2\"");
    }

    private static void assertInvalid(String value) {
        ProjectAccessException error = assertThrows(ProjectAccessException.class,
                () -> RequirementService.parseIfMatch(value));
        assertEquals("INVALID_REQUEST", error.code());
        assertEquals(400, error.status().value());
    }

    private static void assertPreconditionRequired(String value) {
        ProjectAccessException error = assertThrows(ProjectAccessException.class,
                () -> RequirementService.parseIfMatch(value));
        assertEquals("PRECONDITION_REQUIRED", error.code());
        assertEquals(428, error.status().value());
    }
}
