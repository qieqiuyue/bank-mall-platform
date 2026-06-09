package com.bank.auth.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple in-memory rate limiter for login endpoint.
 * Limits per-IP attempts: max 10 in a 60-second window.
 * Evicts stale entries on access.
 */
@Component
public class LoginRateLimiter {
    private static final Logger log = LoggerFactory.getLogger(LoginRateLimiter.class);
    private static final int MAX_ATTEMPTS = 10;
    private static final long WINDOW_SECONDS = 60;

    private final ConcurrentHashMap<String, Window> store = new ConcurrentHashMap<>();

    /**
     * Returns true if the request is within the rate limit.
     * Returns false if the client exceeded MAX_ATTEMPTS within WINDOW_SECONDS.
     */
    public boolean allow(String clientIp) {
        Window w = store.compute(clientIp, (key, existing) -> {
            if (existing == null || existing.isExpired(WINDOW_SECONDS)) {
                return new Window(1);
            }
            return new Window(existing.count + 1);
        });
        if (w.count > MAX_ATTEMPTS) {
            log.warn("Login rate limit exceeded for IP {}: {} attempts in {}s window",
                    clientIp, w.count, WINDOW_SECONDS);
            return false;
        }
        return true;
    }

    public void clear(String clientIp) {
        store.remove(clientIp);
    }

    private record Window(int count) {
        Instant firstSeen = Instant.now();
        boolean isExpired(long windowSeconds) {
            return firstSeen.plusSeconds(windowSeconds).isBefore(Instant.now());
        }
    }
}
