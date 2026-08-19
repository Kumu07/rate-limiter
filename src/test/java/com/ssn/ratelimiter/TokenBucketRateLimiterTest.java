package com.ssn.ratelimiter;

import com.ssn.ratelimiter.model.RateLimitResult;
import com.ssn.ratelimiter.service.TokenBucketRateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Requires a Redis instance reachable at the configured host/port
 * (see docker-compose.yml -> `docker compose up -d redis`).
 */
@SpringBootTest
class TokenBucketRateLimiterTest {

    @Autowired
    private TokenBucketRateLimiter rateLimiter;

    @Test
    void allowsRequestsUpToBucketCapacityThenRejects() {
        String client = "test-client-" + System.nanoTime();
        long capacity = rateLimiter.getBucketCapacity();

        // Consume the full bucket (default capacity = 10)
        for (int i = 0; i < capacity; i++) {
            RateLimitResult result = rateLimiter.tryConsume(client);
            assertTrue(result.allowed(), "Request " + i + " should be allowed within capacity");
        }

        // The very next request should be rejected - bucket is empty
        RateLimitResult overLimit = rateLimiter.tryConsume(client);
        assertFalse(overLimit.allowed(), "Request beyond capacity should be rejected with 429");
    }

    @Test
    void independentClientsHaveIndependentBuckets() {
        String clientA = "client-A-" + System.nanoTime();
        String clientB = "client-B-" + System.nanoTime();

        long capacity = rateLimiter.getBucketCapacity();
        for (int i = 0; i < capacity; i++) {
            assertTrue(rateLimiter.tryConsume(clientA).allowed());
        }
        // Client A is now exhausted, but client B should still have a fresh bucket
        assertTrue(rateLimiter.tryConsume(clientB).allowed());
        assertFalse(rateLimiter.tryConsume(clientA).allowed());
    }
}
