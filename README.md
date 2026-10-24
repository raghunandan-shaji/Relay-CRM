# Scalable CRM — System Design Laboratory

An incremental Java/Spring Boot CRM prototype for UCS3513. The current checkpoint implements customer accounts and several distributed-system components. The full CRM and its scalability evaluation are still in progress.

## Current implementation

| Component | Implemented use |
| --- | --- |
| Snowflake ID generator | Customer IDs; each application replica needs a distinct worker ID |
| Consistent hashing | Customer-ID routing with 256 virtual nodes per configured shard |
| Application-level database sharding | Customer documents spread across 3 independent MongoDB instances |
| Redis key-value store | Global normalized-email-to-customer-ID index |
| JWT authentication | Login and customer-owned profile access without HTTP sessions |
| Atomic Redis rate limiter | Shared login, registration, and authenticated API quotas |
| Optimistic concurrency | Version checks prevent stale profile updates |

The MongoDB instances are standalone databases routed by the application, not a native MongoDB sharded cluster or replica sets. Consistent-hash tests measure key remapping; stored-record migration is not implemented yet.

## Requirements

- Java 21
- Docker with Docker Compose
- Git
- Optional: IntelliJ IDEA/VS Code, Postman, MongoDB Compass

Maven 3.9.11 is provided through the Maven Wrapper; no separate Maven installation is required. The first build/container startup needs internet access for dependencies and images.

## Run locally

From the project root:

```sh
docker compose up -d
```

On a fresh clone, generate a local signing secret for the current terminal session:

```sh
export JWT_SECRET="$(openssl rand -base64 32)"
./mvnw spring-boot:run
```

Do not commit signing secrets. Existing development setups may instead use the ignored `.local/jwt.properties` file described in [authentication.md](docs/authentication.md). Every application replica must share the same signing secret. Regenerating the secret invalidates existing tokens.

The API binds to `http://127.0.0.1:8080`. MongoDB shard ports are 27017, 27018, and 27019; Redis uses 6379. Docker volumes retain local data between restarts. These local databases have no authentication and bind only to loopback; this configuration is not a public deployment.

To stop infrastructure while keeping its data:

```sh
docker compose stop
```

## Customer workflow

| Method and path | Behavior |
| --- | --- |
| `POST /api/customers` | Register; returns ID, shard ID, profile, and version |
| `POST /api/auth/login` | Check email/password; return a 15-minute bearer JWT |
| `GET /api/customers/{id}` | Read the logged-in customer's own profile |
| `PUT /api/customers/{id}` | Update that profile using the latest version |
| `GET /actuator/health/liveness` | Liveness probe |

Registration body:

```json
{
  "fullName": "Demo Customer",
  "email": "unique@example.com",
  "password": "password-123",
  "company": "CRM Lab"
}
```

Login with that email/password, then send the returned `accessToken` as a bearer token. The profile URL must contain the `customerId` from the same login response. MongoDB stores that ID as the document's string `_id`.

Default request allowances per 60-second window: login 10/IP, registration 5/IP, authenticated APIs 120/customer. Exhaustion returns 429 and `Retry-After`. See [rate-limiting.md](docs/rate-limiting.md) for configuration, algorithm, metrics, and a Postman demonstration.

## Verification

```sh
# Regression tests; real-database scenarios are skipped by default
./mvnw test

# Start infrastructure and run real MongoDB/Redis and HTTP scenarios too
docker compose up -d
RUN_INTEGRATION_TESTS=true ./mvnw test
```

Stop any running CRM application before the integration suite to avoid reusing its Snowflake worker ID. The two-server test assigns its own distinct worker IDs. Test signing keys are configured only for the test JVM in Maven Surefire; they are not runtime defaults.

The deliberate shard-outage test has a separate opt-in and expects shard 2 to be stopped. Restore it even if the test fails:

```sh
docker compose stop mongodb-shard-2
RUN_SHARD_FAILURE_TESTS=true ./mvnw -Dtest=MongoShardFailureIntegrationTests test
docker compose start mongodb-shard-2
```

Last full integration-enabled run at this checkpoint: **42 tests discovered, 41 passed, 1 deliberate outage test skipped, 0 failures/errors**. The authentication HTTP test also passed after its quota namespace was isolated for repeat runs.

Selected observed checks:

- 100,000 routing keys: shard shares 33.09%, 33.81%, 33.10%.
- Adding a fourth ring node: 27.50% of keys remapped, compared with 75.01% for the tested modulo baseline. This test does not migrate documents.
- Shared limiter: 100 calls across 20 threads and 2 limiter objects, allowance 25, exactly 25 admitted and 75 rejected.
- Two independent HTTP server contexts on different ports shared all three rate-limit policies. Both ran in one JVM, so this is a functional distribution check, not a multi-process capacity benchmark.
- Real registration/login/profile requests, cross-customer access rejection, stale-update rejection, JWT validation, and shard health checks passed.

## Remaining work and limits

- Customer search, autocomplete, and profile caching
- Leads, opportunities, representative assignment, interactions, support tickets, and notifications
- Load-balancing application replicas and measuring performance at three workload levels
- Controlled baseline comparisons, resource usage, failure/recovery measurements, and the final report
- Replication/failover and safe data migration when changing shard membership

Redis and each MongoDB shard are currently single points of failure. Registration spans Redis and MongoDB without a distributed transaction, so crashes or ambiguous writes can leave the email index inconsistent. The limiter returns 503 when Redis quota checking fails. JWT refresh/revocation, staff permissions, HTTPS termination, and production operational hardening are not implemented yet.

This checkpoint demonstrates implemented components and tested behavior; it does not establish production readiness or million-user capacity.
