package com.mcp.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminUserBCryptTest {

    @Test
    void testBCryptPassword() {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        String validHash = "$2a$10$THsagKBBsV7zgaQFBI2/LOuwZOtlCByx14j/2pbKErQpmdy8Y1YhO";
        boolean matches = encoder.matches("admin1", validHash);
        assertTrue(matches);
    }
}
