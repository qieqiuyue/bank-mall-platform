package com.bank.auth.util;

import com.bank.auth.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JwtUtilTest {

    private JwtUtil jwtUtil;

    @BeforeEach
    void setUp() throws Exception {
        jwtUtil = new JwtUtil();
        // Use reflection to set private fields for testing
        var secretField = JwtUtil.class.getDeclaredField("secret");
        secretField.setAccessible(true);
        secretField.set(jwtUtil, "dGVzdC1zZWNyZXQta2V5LWZvci11bml0LXRlc3RpbmctMzJieXRlcw==");
        var expField = JwtUtil.class.getDeclaredField("expirationHours");
        expField.setAccessible(true);
        expField.set(jwtUtil, 24L);
    }

    @Test
    void generateAndValidate() {
        User user = new User("admin", "pass", "U1001", "Admin", "GOLD", "LOW", "CUSTOMER,MALL_USER");
        String token = jwtUtil.generateToken(user);

        assertNotNull(token);
        assertTrue(token.startsWith("eyJ")); // JWT header starts with "eyJ"

        Claims claims = jwtUtil.validateToken(token);
        assertEquals("U1001", claims.getSubject());
        assertEquals("admin", claims.get("username"));
        assertNotNull(claims.get("roles"));
    }

    @Test
    void tamperedToken() {
        User user = new User("admin", "pass", "U1001", "A", "G", "L", "CUSTOMER");
        String token = jwtUtil.generateToken(user);
        String tampered = token.substring(0, token.length() - 4) + "XXXX";

        assertThrows(JwtException.class, () -> jwtUtil.validateToken(tampered));
    }

    @Test
    void invalidSecret() throws Exception {
        User user = new User("admin", "pass", "U1001", "A", "G", "L", "CUSTOMER");
        String token = jwtUtil.generateToken(user);

        // Create a second JwtUtil with different secret
        JwtUtil otherUtil = new JwtUtil();
        var sf = JwtUtil.class.getDeclaredField("secret");
        sf.setAccessible(true);
        sf.set(otherUtil, "YW5vdGhlci1zZWNyZXQta2V5LWZvci10ZXN0aW5nLXB1cnBvc2Vz");
        var ef = JwtUtil.class.getDeclaredField("expirationHours");
        ef.setAccessible(true);
        ef.set(otherUtil, 24L);

        assertThrows(JwtException.class, () -> otherUtil.validateToken(token));
    }

    @Test
    void isTokenValid_expired() throws Exception {
        // Set expiration to -1 hour (already expired)
        var expField = JwtUtil.class.getDeclaredField("expirationHours");
        expField.setAccessible(true);
        expField.set(jwtUtil, -1L);

        User user = new User("admin", "pass", "U1001", "A", "G", "L", "CUSTOMER");
        String token = jwtUtil.generateToken(user);

        assertFalse(jwtUtil.isTokenValid(token));
    }
}
