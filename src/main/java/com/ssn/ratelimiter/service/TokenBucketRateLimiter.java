package com.ssn.ratelimiter.service;

import com.ssn.ratelimiter.model.RateLimitResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * Implements the Token Bucket algorithm with Redis as the shared, centralized
 * store for bucket state. All the "does this client have a token right now"
 * logic happens inside a single Lua script executed on the Redis server, so
 * the read-refill-compare-write sequence is atomic even when many app
 * instances / threads hit the same client key concurrently.
 */
@Service
public class TokenBucketRateLimiter {

    private static final String KEY_PREFIX = "rate_limit:";

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<List> tokenBucketScript;

    @Value("${ratelimiter.bucket-capacity}")
    private long bucketCapacity;

    @Value("${ratelimiter.refill-rate}")
    private double refillRate;

    @Value("${ratelimiter.tokens-per-request}")
    private long tokensPerRequest;

    @Value("${ratelimiter.key-ttl-seconds}")
    private long keyTtlSeconds;

    public TokenBucketRateLimiter(StringRedisTemplate redisTemplate, RedisScript<List> tokenBucketScript) {
        this.redisTemplate = redisTemplate;
        this.tokenBucketScript = tokenBucketScript;
    }

    /**
     * Checks whether the given client is allowed to make a request right now,
     * consuming a token if so.
     *
     * @param clientId identifier for the caller - API key, user id, or IP address
     */
    @SuppressWarnings("unchecked")
    public RateLimitResult tryConsume(String clientId) {
        String key = KEY_PREFIX + clientId;
        long now = System.currentTimeMillis();

        List<Object> result = redisTemplate.execute(
                tokenBucketScript,
                Collections.singletonList(key),
                String.valueOf(bucketCapacity),
                String.valueOf(refillRate),
                String.valueOf(now),
                String.valueOf(tokensPerRequest),
                String.valueOf(keyTtlSeconds)
        );

        long allowed = Long.parseLong(String.valueOf(result.get(0)));
        double remaining = Double.parseDouble(String.valueOf(result.get(1)));
        long retryAfterMs = Long.parseLong(String.valueOf(result.get(2)));

        return new RateLimitResult(allowed == 1, remaining, retryAfterMs);
    }

    public long getBucketCapacity() {
        return bucketCapacity;
    }

    public double getRefillRate() {
        return refillRate;
    }
}
