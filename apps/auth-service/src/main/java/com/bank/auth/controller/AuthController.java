package com.bank.auth.controller;

import com.bank.common.api.ApiResponse;
import com.bank.auth.entity.User;
import com.bank.auth.repository.UserRepository;
import com.bank.auth.service.LoginRateLimiter;
import com.bank.auth.util.JwtUtil;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "Auth", description = "认证接口 — 登录、令牌验证、用户信息")
public class AuthController {
    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final UserRepository userRepository;
    private final BCryptPasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    private final com.bank.auth.metrics.AuthMetrics metrics;
    private final LoginRateLimiter rateLimiter;

    public AuthController(UserRepository userRepository,
                          BCryptPasswordEncoder passwordEncoder,
                          JwtUtil jwtUtil,
                          com.bank.auth.metrics.AuthMetrics metrics,
                          LoginRateLimiter rateLimiter) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
        this.metrics = metrics;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/login")
    @Operation(summary = "用户登录", description = "使用 username + password 获取 JWT 令牌")
    public ApiResponse<Map<String, Object>> login(@RequestBody(required = false) Map<String, String> body,
                                                   HttpServletRequest request) {
        String clientIp = getClientIp(request);
        if (!rateLimiter.allow(clientIp)) {
            return ApiResponse.error("RATE_LIMITED", "Too many login attempts. Try again later.");
        }

        if (body == null) {
            return ApiResponse.error("BAD_REQUEST", "Missing request body");
        }
        String username = body.getOrDefault("username", "");
        String password = body.getOrDefault("password", "");

        if (username.isEmpty() || password.isEmpty()) {
            return ApiResponse.error("BAD_REQUEST", "Missing username or password");
        }

        // Account-level lockout (credential-stuffing protection): reject immediately
        // when this username has too many consecutive failures.
        if (rateLimiter.isAccountLocked(username)) {
            log.warn("Login rejected for locked account {} (IP {})", username, clientIp);
            metrics.recordLogin("FAILED");
            return ApiResponse.error("ACCOUNT_LOCKED", "Account temporarily locked. Try again later.");
        }

        Optional<User> userOpt = userRepository.findByUsername(username);

        if (userOpt.isPresent() && passwordEncoder.matches(password, userOpt.get().getPassword())) {
            User user = userOpt.get();
            String token = jwtUtil.generateToken(user);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("token", token);
            result.put("userId", user.getUserId() != null ? user.getUserId() : "");
            result.put("username", user.getUsername());
            String rolesStr = user.getRoles() != null ? user.getRoles() : "CUSTOMER";
            result.put("roles", Arrays.asList(rolesStr.split(",")));
            result.put("issuedAt", Instant.now().toString());
            rateLimiter.clear(clientIp);
            rateLimiter.clearAccount(username);
            metrics.recordLogin("SUCCESS");
            return ApiResponse.success("Login successful", result);
        }

        rateLimiter.recordFailure(username);
        metrics.recordLogin("FAILED");
        return ApiResponse.error("AUTH_FAILED", "Invalid username or password");
    }

    /**
     * Resolve the real client IP behind the Ingress proxy.
     * nginx-ingress appends the client address to X-Forwarded-For ($proxy_add_x_forwarded_for),
     * so the LAST address is the trustworthy one — taking the first would let a client
     * spoof any value. Falls back to the socket address when no XFF header is present.
     */
    private String getClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            String[] parts = xff.split(",");
            String last = parts[parts.length - 1].trim();
            if (!last.isEmpty()) {
                return last;
            }
        }
        return request.getRemoteAddr();
    }

    @PostMapping("/validate")
    @Operation(summary = "验证令牌", description = "验证 Bearer JWT 令牌是否有效")
    @SecurityRequirement(name = "BearerAuth")
    public ApiResponse<Map<String, Object>> validate(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return ApiResponse.error("AUTH_FAILED", "Invalid or missing bearer token");
        }
        String token = authorization.substring(7);
        try {
            Claims claims = jwtUtil.validateToken(token);
            return ApiResponse.success("Token is valid", Map.of(
                    "valid", true,
                    "principal", claims.getSubject(),
                    "checkedAt", Instant.now().toString()
            ));
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("Token validation failed: {}", e.getMessage());
            return ApiResponse.error("AUTH_FAILED", "Invalid or expired token");
        }
    }

    @GetMapping("/users/{userId}")
    @Operation(summary = "查询用户信息", description = "根据 userId 查询用户资料（需令牌验证，仅允许查询自己的资料）")
    @SecurityRequirement(name = "BearerAuth")
    public ApiResponse<Map<String, Object>> userProfile(
            @PathVariable String userId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        // Verify JWT subject matches requested userId
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return ApiResponse.error("AUTH_FAILED", "Missing or invalid bearer token");
        }
        String token = authorization.substring(7);
        try {
            Claims claims = jwtUtil.validateToken(token);
            String subject = claims.getSubject();
            if (subject == null || !subject.equals(userId)) {
                log.warn("Access denied: token subject '{}' attempted to access userId '{}'", subject, userId);
                return ApiResponse.error("FORBIDDEN", "You can only access your own profile");
            }
        } catch (JwtException | IllegalArgumentException e) {
            return ApiResponse.error("AUTH_FAILED", "Invalid or expired token");
        }

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
