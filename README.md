# API Rate Limiter — Token Bucket + Redis
UCS3513 System Design Laboratory — Lab Exercise 6

## 1. How to run it

**Prerequisites:** Java 17+, Maven, Docker (for Redis) — or a local Redis install.

```bash
# 1. Start Redis
docker compose up -d redis

# 2. Build and run the Spring Boot app
mvn spring-boot:run
```

The API is now live on `http://localhost:8080`, protected by the rate limiter on every
`/api/**` route.

```bash
# Try it
curl -i -H "X-Client-Id: user1" http://localhost:8080/api/resource
```

Fire the bundled traffic script to see normal vs. burst behaviour:

```bash
chmod +x load-test/traffic_test.sh
./load-test/traffic_test.sh
```

Run the JUnit tests (needs Redis running):

```bash
mvn test
```

## 2. Configuration

`src/main/resources/application.properties`:

| Property | Meaning | Default |
|---|---|---|
| `ratelimiter.bucket-capacity` | max tokens a bucket can hold = burst allowance | 10 |
| `ratelimiter.refill-rate` | tokens added back per second = sustained rate | 2 |
| `ratelimiter.tokens-per-request` | tokens consumed per request | 1 |
| `ratelimiter.key-ttl-seconds` | Redis key expiry for idle clients | 3600 |

With the defaults: a client can burst up to 10 requests instantly, then is limited to
2 requests/second thereafter.

## 3. Project layout

```
rate-limiter/
├── pom.xml
├── docker-compose.yml
├── load-test/traffic_test.sh
└── src/main/java/com/ssn/ratelimiter/
    ├── RateLimiterApplication.java        entry point
    ├── config/RedisConfig.java            Redis + Lua script bean
    ├── config/WebConfig.java              registers the interceptor
    ├── filter/RateLimitInterceptor.java   runs before every /api/** controller
    ├── service/TokenBucketRateLimiter.java  calls the Lua script, exposes tryConsume()
    ├── model/RateLimitResult.java         allowed / remaining / retryAfter
    └── controller/DemoController.java     sample protected endpoint
    resources/scripts/token_bucket.lua     atomic refill+consume logic
```

---

## 4. Analysis

### Q1. Role of each component

- **Token Bucket** — a conceptual bucket, per client, that holds a limited number of
  "tokens." Each incoming request must remove one token to proceed. If no token is
  available, the request is rejected. It is the accounting unit for how much traffic a
  client has "earned" the right to send.
- **Bucket Capacity** — the maximum number of tokens the bucket can ever hold. This is
  the burst allowance: how many requests a client can fire back-to-back before being
  throttled, even if they'd been idle beforehand.
- **Refill Rate** — the rate (tokens/second) at which the bucket is topped up over
  time, up to the capacity. This sets the long-run sustained request rate a client is
  allowed once the initial burst allowance is used up.
- **Redis** — the centralized, shared store that holds each client's current token
  count and the timestamp of the last update. Because it is external to any single
  application instance and supports atomic scripted operations, it lets every replica
  of the service enforce one consistent limit per client instead of each instance
  keeping its own (inconsistent) in-memory count.

### Q2. How Token Bucket allows or rejects requests

On every request the algorithm:
1. Reads the client's current token count and the timestamp it was last updated.
2. Computes elapsed time since that timestamp and refills tokens proportionally
   (`elapsed_seconds × refill_rate`), capped at `bucket_capacity`.
3. If the refilled count ≥ tokens required for this request, it subtracts the tokens
   and **allows** the request.
4. Otherwise it leaves the (insufficient) token count as is and **rejects** the
   request with HTTP 429, optionally telling the client how long to wait
   (`retryAfterMillis`) until enough tokens will have accumulated.

This "lazy refill" design means there's no background timer topping up buckets — the
refill math is simply computed at request time based on elapsed time, which is cheap
and scales to any number of clients.

### Q3. Why Redis is required with multiple application instances

If each application instance kept token counts in local memory, a client hitting a
load balancer that round-robins across instances would effectively get
`capacity × number_of_instances` tokens, since each instance would track the client
independently and know nothing about requests handled by its siblings. That defeats
the purpose of a global limit. Redis solves this by acting as a single shared source
of truth: every instance reads and writes the same key
(`rate_limit:<clientId>`) for a given client, so the limit is enforced consistently
network-wide regardless of which instance handles the request. Redis's atomic
scripting (Lua/`EVAL`) additionally prevents race conditions when multiple instances
try to update the same client's bucket at the same moment — the whole
read-refill-compare-write happens as one indivisible operation on the Redis server.

### Q4. Comparing rate-limiting algorithms

| Algorithm | How it works | Pros | Cons |
|---|---|---|---|
| **Fixed Window** | Count requests in a fixed time slot (e.g. per-minute) using a simple counter; reset each slot. | Very simple, cheap (one counter). | Boundary burst problem: a client can send the full quota at the end of one window and again at the start of the next, doubling the effective rate momentarily. |
| **Sliding Window Log** | Store a timestamp per request in a sorted set; count entries within the last `N` seconds on each request. | Perfectly precise, no boundary burst issue. | Memory/CPU heavy — stores every individual request timestamp, expensive at high volume. |
| **Sliding Window Counter** | Approximates the sliding log by weighting the previous and current fixed windows' counters proportionally to overlap. | Much cheaper than the log approach, smooths out the fixed-window boundary spike. | Only an approximation, not exact under all traffic patterns. |
| **Token Bucket** | Tokens accumulate at a steady rate up to a cap; each request consumes a token. | Naturally allows short bursts up to capacity while enforcing a long-run average rate; O(1) state per client (just tokens + timestamp); simple to reason about. | Slightly more complex to implement correctly (needs the atomic refill logic) than a fixed counter; burst tolerance can be a double-edged sword if capacity is set too high. |

Token Bucket is generally preferred for public APIs because it tolerates natural burst
traffic (e.g. a user firing several requests when a page loads) while still capping the
sustained rate — the fixed and sliding-window approaches either allow boundary bursts
(fixed) or are exact but expensive (sliding log), with sliding counter as a middle
ground.

### Q5. Behaviour under normal vs. burst traffic

- **Normal traffic** (requests spaced out at or below the refill rate, e.g. ≤2/sec with
  the default config): every request finds a token already available (refilled since
  the last call) and is allowed. The bucket essentially stays near full; the client
  never notices the limiter.
- **Burst traffic** (many requests fired almost simultaneously, e.g. 20 requests at
  once with capacity 10): the first 10 requests succeed by draining the bucket to zero
  in milliseconds — far faster than the refill rate can replenish it. The remaining 10
  requests arrive to find an empty bucket and are rejected with HTTP 429, each carrying
  a `retryAfterMillis` hint. As time passes, tokens trickle back in at the configured
  refill rate, and subsequent requests start succeeding again once enough tokens have
  accumulated. This is exactly the pattern the bundled `traffic_test.sh` script is
  designed to demonstrate.
