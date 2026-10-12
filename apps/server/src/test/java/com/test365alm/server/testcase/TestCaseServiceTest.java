package com.test365alm.server.testcase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import com.test365alm.server.project.ProjectAccessException;

class TestCaseServiceTest {
    @Test
    void ifMatchAcceptsOnlyOneStrongQuotedPositiveTag() {
        assertEquals(1L, TestCaseService.parseIfMatch("\"1\""));
        assertEquals(42L, TestCaseService.parseIfMatch("\"42\""));
    }

    @Test
    void ifMatchRejectsBareWeakWildcardAndMalformedValues() {
        assertInvalid("1");
        assertInvalid("W/\"1\"");
        assertPreconditionRequired(null);
        assertInvalid("\"0\"");
        assertInvalid("\"1");
        assertInvalid("1\"");
        assertInvalid("\"1\"\"2\"");
    }

    private static void assertInvalid(String value) {
        ProjectAccessException error = assertThrows(ProjectAccessException.class,
                () -> TestCaseService.parseIfMatch(value));
        assertEquals("INVALID_REQUEST", error.code());
        assertEquals(400, error.status().value());
    }

    private static void assertPreconditionRequired(String value) {
        ProjectAccessException error = assertThrows(ProjectAccessException.class,
                () -> TestCaseService.parseIfMatch(value));
        assertEquals("PRECONDITION_REQUIRED", error.code());
        assertEquals(428, error.status().value());
    }
}

