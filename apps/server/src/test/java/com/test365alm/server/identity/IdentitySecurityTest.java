package com.test365alm.server.identity;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class IdentitySecurityTest {
    @Test
    void insecureCookieIsLimitedToLiteralLoopbackDevelopmentOrigin() {
        assertDoesNotThrow(() -> IdentitySecurity.enforceCookieBoundary(
                "127.0.0.1", "http://127.0.0.1:5173", false));
        assertThrows(IllegalStateException.class, () -> IdentitySecurity.enforceCookieBoundary(
                "0.0.0.0", "http://127.0.0.1:5173", false));
        assertThrows(IllegalStateException.class, () -> IdentitySecurity.enforceCookieBoundary(
                "127.0.0.1", "http://public.example.invalid", false));
        assertThrows(IllegalStateException.class, () -> IdentitySecurity.enforceCookieBoundary(
                "127.0.0.1", "http://127.0.0.1:5173@public.example.invalid", false));
        assertThrows(IllegalStateException.class, () -> IdentitySecurity.enforceCookieBoundary(
                "0.0.0.0", "http://public.example.invalid", true));
        assertDoesNotThrow(() -> IdentitySecurity.enforceCookieBoundary(
                "0.0.0.0", "https://alm.example.invalid", true));
    }
}
