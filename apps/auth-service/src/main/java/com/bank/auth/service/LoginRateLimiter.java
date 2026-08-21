package com.bank.auth.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory rate limiter for the login endpoint.
 *
 * Two layers of protection:
 *  1. Per-IP attempt limit (max {@value #MAX_ATTEMPTS} in a 60s window) — the
 *     client IP must be the real client (see AuthController#getClientIp, which
 *     resolves X-Forwarded-For so all users don't share the Ingress Pod IP).
 *  2. Per-account lockout: after {@value #MAX_ACCOUNT_FAILURES} consecutive
 *     failed logins for a username, further attempts are rejected for
 *     {@value #ACCOUNT_LOCK_MINUTES} minutes (credential-stuffing protection).
 *
 * Entries are evicted lazily on access; when the map grows beyond a threshold
 * a full expired-entry sweep runs (no @Scheduled dependency needed).
 */
@Component
public class LoginRateLimiter {
    private static final Logger log = LoggerFactory.getLogger(LoginRateLimiter.class);
    private static final int MAX_ATTEMPTS = 10;
    private static final long WINDOW_SECONDS = 60;
    private static final int MAX_ACCOUNT_FAILURES = 5;
    private static final long ACCOUNT_LOCK_MINUTES = 15;
    private static final int CLEANUP_THRESHOLD = 1024;

    private final ConcurrentHashMap<String, Window> store = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AccountFailures> accountStore = new ConcurrentHashMap<>();

    /**
     * Returns true if the request is within the IP rate limit.
     * Returns false if the client exceeded MAX_ATTEMPTS within WINDOW_SECONDS.
     */
    public boolean allow(String clientIp) {
        maybeSweep();
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

    /**
     * True if the account is currently locked out (too many consecutive failures).
     */
    public boolean isAccountLocked(String username) {
        AccountFailures f = accountStore.get(username);
        return f != null && f.isLocked();
    }

    /** Record a failed login attempt for the account; locks it once the threshold is hit. */
    public void recordFailure(String username) {
        AccountFailures f = accountStore.compute(username, (key, existing) -> {
            if (existing == null || existing.isExpired()) {
                return new AccountFailures(1);
            }
            return new AccountFailures(existing.count + 1);
        });
        if (f.count >= MAX_ACCOUNT_FAILURES) {
            log.warn("Account {} locked for {} minutes after {} consecutive failures",
                    username, ACCOUNT_LOCK_MINUTES, f.count);
        }
    }

    /** Clear per-account failure counter on successful login. */
    public void clearAccount(String username) {
        accountStore.remove(username);
    }

    public void clear(String clientIp) {
        store.remove(clientIp);
    }

    /**
     * Bound the map size: when it grows past the threshold, drop all expired
     * windows in one pass. Keeps memory bounded without a scheduler.
     */
    private void maybeSweep() {
        if (store.size() > CLEANUP_THRESHOLD) {
            store.entrySet().removeIf(e -> e.getValue().isExpired(WINDOW_SECONDS));
        }
        if (accountStore.size() > CLEANUP_THRESHOLD) {
            accountStore.entrySet().removeIf(e -> e.getValue().isExpired());
        }
    }

    private static class Window {
        final int count;
        final Instant firstSeen = Instant.now();
        Window(int count) { this.count = count; }
        boolean isExpired(long windowSeconds) {
            return firstSeen.plusSeconds(windowSeconds).isBefore(Instant.now());
        }
    }

    private static class AccountFailures {
        final int count;
        final Instant firstSeen = Instant.now();
        AccountFailures(int count) { this.count = count; }
        /** Lockout window: MAX_ACCOUNT_FAILURES within ACCOUNT_LOCK_MINUTES triggers lock. */
        boolean isExpired() {
            return firstSeen.plusSeconds(ACCOUNT_LOCK_MINUTES * 60).isBefore(Instant.now());
        }
        boolean isLocked() {
            return count >= MAX_ACCOUNT_FAILURES && !isExpired();
        }
    }
}
