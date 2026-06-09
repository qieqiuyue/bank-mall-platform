package com.bank.auth.controller;

import com.bank.auth.entity.User;
import com.bank.auth.repository.UserRepository;
import com.bank.auth.service.LoginRateLimiter;
import com.bank.auth.util.JwtUtil;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.impl.DefaultClaims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AuthControllerTest {

    private MockMvc mvc;
    private UserRepository userRepository;
    private BCryptPasswordEncoder passwordEncoder;
    private JwtUtil jwtUtil;
    private LoginRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        passwordEncoder = mock(BCryptPasswordEncoder.class);
        jwtUtil = mock(JwtUtil.class);
        rateLimiter = mock(LoginRateLimiter.class);
        when(rateLimiter.allow(anyString())).thenReturn(true);
        com.bank.auth.metrics.AuthMetrics metrics = mock(com.bank.auth.metrics.AuthMetrics.class);
        mvc = MockMvcBuilders.standaloneSetup(
                new AuthController(userRepository, passwordEncoder, jwtUtil, metrics, rateLimiter)).build();
    }

    @Test
    void login_success() throws Exception {
        User user = new User("admin", "$2a$encoded", "U1001", "Admin", "GOLD", "LOW", "CUSTOMER,MALL_USER");
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("123456", "$2a$encoded")).thenReturn(true);
        when(jwtUtil.generateToken(any())).thenReturn("eyJhbGciOiJIUzI1NiJ9.mock-token");

        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.token").value("eyJhbGciOiJIUzI1NiJ9.mock-token"))
                .andExpect(jsonPath("$.data.userId").value("U1001"));
    }

    @Test
    void login_wrongPassword() throws Exception {
        User user = new User("admin", "$2a$encoded", "U1001", "Admin", "GOLD", "LOW", "CUSTOMER");
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "$2a$encoded")).thenReturn(false);

        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"wrong\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("AUTH_FAILED"));
    }

    @Test
    void login_missingBody() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void validate_missingHeader() throws Exception {
        mvc.perform(post("/api/auth/validate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("AUTH_FAILED"));
    }

    @Test
    void userProfile_found() throws Exception {
        User user = new User("admin", "pass", "U1001", "Demo User", "GOLD", "LOW", "CUSTOMER");
        when(userRepository.findByUserId("U1001")).thenReturn(Optional.of(user));

        Claims claims = new DefaultClaims();
        claims.setSubject("U1001");
        when(jwtUtil.validateToken("test-token")).thenReturn(claims);

        mvc.perform(get("/api/auth/users/U1001")
                        .header("Authorization", "Bearer test-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.name").value("Demo User"));
    }

    @Test
    void userProfile_notFound() throws Exception {
        when(userRepository.findByUserId("GHOST")).thenReturn(Optional.empty());

        Claims claims = new DefaultClaims();
        claims.setSubject("GHOST");
        when(jwtUtil.validateToken("test-token")).thenReturn(claims);

        mvc.perform(get("/api/auth/users/GHOST")
                        .header("Authorization", "Bearer test-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void health() throws Exception {
        when(userRepository.count()).thenReturn(3L);

        mvc.perform(get("/api/auth/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.status").value("UP"))
                .andExpect(jsonPath("$.data.users").value(3));
    }
}
