package com.testagent.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * vT2: JWT 签发/解析/校验基线测试。
 */
class JwtUtilTest {

    private static final String SECRET = "01234567890123456789012345678901";

    private JwtUtil newUtil(long expirationHours) {
        return new JwtUtil(SECRET, expirationHours);
    }

    @Test
    void tokenRoundTrip() {
        JwtUtil util = newUtil(12);
        String token = util.generateToken("admin", "ADMIN");
        assertTrue(util.isValid(token));
        assertEquals("admin", util.extractUsername(token));
        assertEquals("ADMIN", util.extractRole(token));
    }

    @Test
    void invalidTokenRejected() {
        JwtUtil util = newUtil(12);
        assertFalse(util.isValid("bad.token.here"));
        assertFalse(util.isValid(null));
    }

    @Test
    void expiredTokenRejected() {
        JwtUtil util = newUtil(0);
        String token = util.generateToken("admin", "ADMIN");
        assertFalse(util.isValid(token));
    }
}
