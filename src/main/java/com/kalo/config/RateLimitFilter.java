package com.kalo.config;

import com.kalo.common.exception.ErrorResponseWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Caps how often one client may call the endpoints that are open to anonymous
 * callers.
 *
 * These three are the only unauthenticated write-or-work paths in the API:
 * login can be brute-forced against bcrypt hashes, registration can fill the
 * user table, and the public availability endpoint runs a multi-table query for
 * anyone who asks.
 *
 * Deliberately a small in-process token bucket rather than a dependency. It is
 * enough to stop a single host hammering the API, it cannot break at a version
 * boundary, and it keeps working when the application is reached directly
 * rather than through a proxy. Its limits are per instance and reset on
 * restart, so a multi-instance deployment facing real abuse still wants a
 * limiter at the edge — this is the floor, not the ceiling.
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class RateLimitFilter extends OncePerRequestFilter {

    private final ErrorResponseWriter errorResponseWriter;

    /** Tight: a person signing in gets this wrong a handful of times, not 30. */
    private static final Limit LOGIN = new Limit(10, 60);

    /** Registration is once per person, so anything repeated is suspicious. */
    private static final Limit REGISTER = new Limit(5, 300);

    /** Generous enough for a visitor tapping around the map, not for a script. */
    private static final Limit PUBLIC = new Limit(30, 60);

    private static final Map<String, Limit> LIMITED_PATHS = Map.of(
            "/api/v1/auth/login", LOGIN,
            "/api/v1/auth/register/customer", REGISTER,
            "/api/v1/auth/register/partner", REGISTER,
            "/api/v1/public/taxi-availability", PUBLIC
    );

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final AtomicLong lastSweep = new AtomicLong(System.currentTimeMillis());

    /**
     * Set when the application sits behind a proxy that is known to append the
     * real client IP. Off by default: trusting the header unconditionally would
     * let any caller forge their identity and get a fresh bucket per request.
     */
    @Value("${app.rate-limit.trust-forwarded-for:false}")
    private boolean trustForwardedFor;

    @Value("${app.rate-limit.enabled:true}")
    private boolean enabled;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        Limit limit = enabled ? LIMITED_PATHS.get(request.getRequestURI()) : null;

        if (limit == null) {
            filterChain.doFilter(request, response);
            return;
        }

        sweepIfDue();

        String key = request.getRequestURI() + "|" + clientIp(request);
        Bucket bucket = buckets.computeIfAbsent(key, unused -> new Bucket(limit));

        if (bucket.tryConsume()) {
            filterChain.doFilter(request, response);
            return;
        }

        log.warn(
                "Rate limit hit: path={} retryAfterSeconds={}",
                request.getRequestURI(),
                limit.windowSeconds()
        );

        writeTooManyRequests(request, response, limit.windowSeconds());
    }

    /** Same JSON shape as every other error the API returns. */
    private void writeTooManyRequests(
            HttpServletRequest request,
            HttpServletResponse response,
            int retryAfterSeconds
    ) throws IOException {

        response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));

        errorResponseWriter.write(
                request,
                response,
                HttpStatus.TOO_MANY_REQUESTS,
                "Too many attempts. Please wait a moment and try again."
        );
    }

    /**
     * Clears all counters. Exists for tests, which need each case to start from
     * a known state — the filter is a singleton, so one test's attempts would
     * otherwise be counted against the next. Package-private so it is not part
     * of the application's API.
     */
    void resetLimits() {
        buckets.clear();
    }

    private String clientIp(HttpServletRequest request) {

        if (trustForwardedFor) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                // Left-most entry is the original client.
                return forwarded.split(",")[0].trim();
            }
        }

        return request.getRemoteAddr();
    }

    /**
     * Drops buckets nobody has touched for a while, so the map cannot grow
     * without bound from one-off callers.
     */
    private void sweepIfDue() {

        long now = System.currentTimeMillis();
        long previous = lastSweep.get();

        if (now - previous < 300_000 || !lastSweep.compareAndSet(previous, now)) {
            return;
        }

        List<String> stale = buckets.entrySet().stream()
                .filter(entry -> entry.getValue().isIdleSince(now - 600_000))
                .map(Map.Entry::getKey)
                .toList();

        stale.forEach(buckets::remove);
    }

    private record Limit(int permits, int windowSeconds) {
    }

    /**
     * Fixed window per key. Simpler than a sliding window and adequate here:
     * the worst case is a caller getting up to two windows' worth of requests
     * across a boundary, which none of these limits care about.
     */
    private static final class Bucket {

        private final Limit limit;
        private long windowStart;
        private int used;

        private Bucket(Limit limit) {
            this.limit = limit;
            this.windowStart = System.currentTimeMillis();
        }

        synchronized boolean tryConsume() {

            long now = System.currentTimeMillis();

            if (now - windowStart >= limit.windowSeconds() * 1000L) {
                windowStart = now;
                used = 0;
            }

            if (used >= limit.permits()) {
                return false;
            }

            used++;
            return true;
        }

        synchronized boolean isIdleSince(long threshold) {
            return windowStart < threshold;
        }
    }
}
