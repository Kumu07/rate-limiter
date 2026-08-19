package com.ssn.ratelimiter.filter;

import org.springframework.http.HttpStatus;
import com.ssn.ratelimiter.model.RateLimitResult;
import com.ssn.ratelimiter.service.TokenBucketRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Intercepts every HTTP request before it reaches a controller, identifies
 * the caller, and asks the TokenBucketRateLimiter whether it may proceed.
 * If not, responds immediately with HTTP 429 and does not invoke the
 * controller at all.
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final String CLIENT_HEADER = "X-Client-Id";

    private final TokenBucketRateLimiter rateLimiter;

    public RateLimitInterceptor(TokenBucketRateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String clientId = resolveClientId(request);

        RateLimitResult result = rateLimiter.tryConsume(clientId);

        // Standard rate-limit response headers, useful for clients and for the burst-test script
        response.setHeader("X-RateLimit-Limit", String.valueOf(rateLimiter.getBucketCapacity()));
        response.setHeader("X-RateLimit-Remaining", String.valueOf((long) result.remainingTokens()));

        if (!result.allowed()) {
            response.setHeader("Retry-After-Millis", String.valueOf(result.retryAfterMillis()));
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value()); // 429
            response.setContentType("application/json");
            try {
                response.getWriter().write(
                        "{\"error\":\"Too Many Requests\",\"clientId\":\"" + clientId +
                        "\",\"retryAfterMillis\":" + result.retryAfterMillis() + "}"
                );
            } catch (Exception ignored) {
                // response stream already closed by container; nothing further to do
            }
            return false; // stop the request here - controller is never called
        }

        return true;
    }

    /**
     * Identify the caller. In production this would typically be an API key
     * or authenticated user id; here we also allow overriding via a header
     * so the burst-test script can simulate multiple distinct clients easily,
     * and fall back to the remote IP address.
     */
    private String resolveClientId(HttpServletRequest request) {
        String header = request.getHeader(CLIENT_HEADER);
        if (header != null && !header.isBlank()) {
            return header.trim();
        }
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
