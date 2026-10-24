# Shared Redis rate limiter

## Purpose and component mapping

The rate limiter protects customer registration and login from repeated attempts, and bounds one customer's API usage across CRM replicas. Redis is the shared key-value store. Keeping quota state there means adding an application server does not multiply a client's allowance.

## Default policies

| Policy | Identity | Allowed requests per window |
| --- | --- | ---: |
| Login: POST `/api/auth/login` | Client socket IP | 10 |
| Registration: POST `/api/customers` | Client socket IP | 5 |
| Authenticated `/api/**` requests | Verified JWT customer ID | 120 |

Each policy/identity gets its own 60-second window starting at its first admitted request. These limits are starting settings, not measured production capacity. Failed login, invalid input, and downstream errors still consume an admitted request. Health endpoints bypass the limiter. Anonymous protected requests are still rejected by Spring Security; malformed/invalid JWTs are rejected before the limiter.

Login and registration use their IP policy even if a valid token is supplied. Profiles use customer ID so changing JWTs or server instances does not reset the quota. All replicas must share the same Redis, key prefix, limits, and window configuration.

## Algorithm and integration

The filter runs after Spring's bearer authentication filter and before authorization/controllers. It calls one parameterized Redis Lua script. The script reads the counter, initializes it with a millisecond expiry, or increments an unexhausted counter. Rejected calls do not extend expiry or increment the counter. Counter changes and expiry management execute atomically in Redis.

The script uses Redis TTLs, avoiding application-server clock skew. Spring Data Redis handles script caching and reload. Each quota uses one key, so the script does not require cross-key transactions.

Responses include:

- `X-RateLimit-Limit`: total allowance.
- `X-RateLimit-Remaining`: allowance after this admission.
- `X-RateLimit-Reset-After`: seconds until expiry, rounded up.
- `Retry-After` on 429: seconds to wait before retrying.

An exhausted quota returns HTTP 429 with an `application/problem+json` body. Redis connection/script failures return 503 before business code runs. The Redis connection and command timeouts are 1 second; outage recovery/reconnection may add overhead. The limiter does not affect `/actuator/health/liveness`; general readiness/health can still report database outages.

The filter is created inside the security chain, not registered again as a servlet filter, so it is not accidentally applied twice.

## What to do in Postman

No installation is needed. The app runs at `http://127.0.0.1:8080`.

1. Create a POST request to `/api/auth/login`. Set Authorization to No Auth and Body to raw JSON containing `{}`.
2. Send it 11 times within 60 seconds. With a fresh quota, the first 10 return 400 because fields are missing. The 11th returns 429 before input validation.
3. Inspect response Headers: `X-RateLimit-Remaining` falls to 0; the rejected response includes `Retry-After`.
4. Wait for that delay, then send once more: input validation returns 400 again because the quota has reset.
5. Replace `{}` with real credentials to log in normally. Successful logins also use the quota. Previous login attempts may cause 429 earlier than the 11th request.

This test does not create customer records. The response changes from 400 to 429 because the limiter runs before request validation. Do not attach an expired bearer token to the login request; an invalid token is rejected by the security filter before quota checking.

## Configuration

Set these environment variables before starting the app:

| Variable | Default |
| --- | --- |
| `RATE_LIMIT_LOGIN` | 10 |
| `RATE_LIMIT_REGISTRATION` | 5 |
| `RATE_LIMIT_API` | 120 |
| `RATE_LIMIT_WINDOW` | 60s |

The `crm.rate-limit.key-prefix` property defaults to `crm:rate-limit:`. Integration tests use their own namespaces and delete only keys and temporary customer records they create. Expired quota keys are removed automatically. Changing the key prefix resets all quotas, so replicas must use the same prefix.

The application ignores forwarded-IP headers (`server.forward-headers-strategy=none`). Before deploying behind a reverse proxy, explicitly configure trusted proxy handling so client identity is correct without accepting spoofed headers. Users behind one NAT currently share login/registration quotas.

## Metrics and observed verification

Micrometer records:

- `crm.rate_limit.requests`, tagged by `policy` and `outcome` (`allowed`, `rejected`, `unavailable`). Rejection rate = rejected / (allowed + rejected). Track unavailable separately.
- `crm.rate_limit.lookup`, tagged by `policy`, recording count and lookup duration including failures. Tags never include customer IDs or IPs.

Meters are currently inspected in tests; no public metrics endpoint is exposed.

Observed on the local development machine on 25 October 2026:

| Scenario | Measured result |
| --- | --- |
| 2 limiter objects, 20 threads, 100 calls, shared allowance 25 | 25 admitted, 75 rejected (75% rejection), zero overshoot |
| Quota expiry | Exhausted quota admits again after expiry |
| 2 running HTTP servers on different ports, login limit 4 | Combined 4 admitted; further calls to either server return 429 |
| Same 2 HTTP servers, registration limit 2 | Combined 2 admitted; further request returns 429 |
| Same 2 HTTP servers, customer API limit 3 | Combined 3 admitted for one customer; both servers then return 429 |
| Different authenticated customer | Still admitted after the first customer's allowance is exhausted |
| Liveness after quota exhaustion | HTTP 200 |
| Simulated Redis failure in filter test | HTTP 503; controller not called |

The HTTP servers are independent Spring application contexts in one JVM. This proves shared-quota behavior across server instances. It does not measure separate-process/machine scaling, throughput, resource usage, or performance across three workload levels. Those remain part of the full-system performance stage.

Run verification:

```sh
./mvnw test
RUN_INTEGRATION_TESTS=true ./mvnw test
```

## Limits and tradeoffs

- A fixed window is simple and uses O(1) Redis state per active policy/client, but allows a burst near a window boundary. Token buckets would smooth traffic at the cost of more algorithm/state complexity.
- Redis is currently a single instance and adds a network round trip to each limited request. Returning 503 when unavailable protects the quota guarantee but reduces availability. Redis failover, replication, and controlled outage testing are not implemented here.
- This bounds admitted business operations, not total internet traffic or signature-verification work. A deployment still needs edge protections, connection limits, and HTTPS.
- Limits are per IP or customer; there is no server-wide admission cap yet.
- No new dependencies were added.

References: [Redis Lua atomic execution](https://redis.io/docs/latest/develop/programmability/eval-intro/), [Redis Spring rate-limiting tutorial](https://redis.io/tutorials/rate-limiting-in-java-spring-with-redis.md).
