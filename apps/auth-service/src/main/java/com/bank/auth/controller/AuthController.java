package com.bank.auth.controller;

import com.bank.auth.api.ApiResponse;
import com.bank.auth.entity.User;
import com.bank.auth.repository.UserRepository;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserRepository userRepository;
    private final Map<String, User> tokenStore = new java.util.concurrent.ConcurrentHashMap<>();

    public AuthController(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @PostMapping("/login")
    public ApiResponse<Map<String, Object>> login(@RequestBody(required = false) Map<String, String> body) {
        if (body == null) {
            return ApiResponse.error("BAD_REQUEST", "Missing request body");
        }
        String username = body.getOrDefault("username", "");
        String password = body.getOrDefault("password", "");

        if (username.isEmpty() || password.isEmpty()) {
            return ApiResponse.error("BAD_REQUEST", "Missing username or password");
        }

        Optional<User> userOpt = userRepository.findByUsername(username);

        if (userOpt.isPresent() && password.equals(userOpt.get().getPassword())) {
            User user = userOpt.get();
            String token = "token-" + UUID.randomUUID();
            tokenStore.put(token, user);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("token", token);
            result.put("userId", user.getUserId() != null ? user.getUserId() : "");
            result.put("username", user.getUsername());
            String rolesStr = user.getRoles() != null ? user.getRoles() : "CUSTOMER";
            result.put("roles", Arrays.asList(rolesStr.split(",")));
            result.put("issuedAt", Instant.now().toString());
            return ApiResponse.success("Login successful", result);
        }

        return ApiResponse.error("AUTH_FAILED", "Invalid username or password");
    }

    @PostMapping("/validate")
    public ApiResponse<Map<String, Object>> validate(@RequestHeader(value = "Authorization", required = false) String authorization) {
        if (authorization != null && authorization.startsWith("Bearer ")) {
            String token = authorization.substring(7);
            User user = tokenStore.get(token);
            if (user != null) {
                return ApiResponse.success("Token is valid", Map.of(
                    "valid", true,
                    "principal", user.getUserId() != null ? user.getUserId() : "unknown",
                    "checkedAt", Instant.now().toString()
                ));
            }
        }
        return ApiResponse.error("AUTH_FAILED", "Invalid or missing bearer token");
    }

    @GetMapping("/users/{userId}")
    public ApiResponse<Map<String, Object>> userProfile(@PathVariable String userId) {
        return userRepository.findByUserId(userId)
            .map(user -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("userId", user.getUserId() != null ? user.getUserId() : "");
                m.put("name", user.getDisplayName() != null ? user.getDisplayName() : "");
                m.put("level", user.getLevel() != null ? user.getLevel() : "N/A");
                m.put("riskLevel", user.getRiskLevel() != null ? user.getRiskLevel() : "N/A");
                return ApiResponse.success(m);
            })
            .orElse(ApiResponse.error("NOT_FOUND", "User not found: " + userId));
    }

    @GetMapping("/health")
    public ApiResponse<Map<String, Object>> health() {
        return ApiResponse.success("auth-service is healthy", Map.of(
            "status", "UP",
            "service", "auth-service",
            "users", userRepository.count()
        ));
    }
}
