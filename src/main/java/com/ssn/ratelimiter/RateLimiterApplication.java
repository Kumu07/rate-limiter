package com.ssn.ratelimiter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * UCS3513 - System Design Laboratory
 * Lab Exercise 6: API Rate Limiter using Token Bucket algorithm + Redis
 */
@SpringBootApplication
public class RateLimiterApplication {

    public static void main(String[] args) {
        SpringApplication.run(RateLimiterApplication.class, args);
    }
}
