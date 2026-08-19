package com.ssn.ratelimiter.model;

/**
 * Outcome of a single rate-limit check.
 *
 * @param allowed          whether the request may proceed
 * @param remainingTokens  tokens left in the bucket after this decision
 * @param retryAfterMillis how long the client should wait before retrying (0 if allowed)
 */
public record RateLimitResult(boolean allowed, double remainingTokens, long retryAfterMillis) {
}
