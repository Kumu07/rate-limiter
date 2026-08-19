package com.ssn.ratelimiter.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * A trivial protected resource used to demonstrate/test the rate limiter.
 * Every call to /api/** first passes through RateLimitInterceptor.
 */
@RestController
public class DemoController {

    @GetMapping("/api/resource")
    public Map<String, Object> getResource() {
        return Map.of(
                "message", "Request processed successfully",
                "timestamp", Instant.now().toString()
        );
    }

    @GetMapping("/api/health")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }
}
