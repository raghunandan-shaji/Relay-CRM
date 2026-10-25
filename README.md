# Relay CRM — System Design Laboratory

A Java/Spring Boot CRM prototype for UCS3513, with a basic browser frontend, customer/staff workflows, three application processes, customer-sharded MongoDB storage, and reproducible performance/failure experiments. The documented limits are part of the result; this is not a claim of production or million-user readiness.

## Current implementation

| Component | Implemented use |
| --- | --- |
| Snowflake ID generator | Customer, lead, opportunity, interaction, and activity IDs; each application replica needs a distinct worker ID |
| Consistent hashing | Customer-ID routing with 256 virtual nodes per configured shard |
| Application-level database sharding | Customers spread across 3 independent MongoDB instances; their leads, interactions, and activities are colocated |
| Redis key-value store | Global normalized-email-to-customer-ID index |
| JWT authentication | Login and customer-owned profile access without HTTP sessions |
| Atomic Redis rate limiter | Shared login, registration, and authenticated API quotas |
| Optimistic concurrency | Version checks prevent stale profile updates |
| Indexed search and autocomplete | Staff-only normalized name-prefix lookup across all shards |
| Shared profile cache | Redis caches public profiles with TTL and version-aware writes |
| Sales pipeline | Customer-shard lead storage, atomic opportunity qualification, and versioned deal stages |
| Representative assignment | Consistent hashing over registered staff accounts; assignment is persisted |
| Interaction history | Separate append-only records, indexed event-time cursor pagination, staff writes and owner/staff reads |
| Sales activities | Planned work, representative assignment, filtered pagination, versioned completion/cancellation |
| Customer support | Customer-owned tickets/conversations, staff queue/assignment, versioned status/replies |
| Durable in-app notifications | Single-record atomic outbox, retrying workers, duplicate suppression, owner-only inbox acknowledgement |
| Basic frontend | Locally served HTML/CSS/JavaScript; role-aware customer/staff workflows |
| Staff account directory | Bounded, indexed shard queries and merged cursor pages; staff-only browsing |
| Application scale-out | Independent Java containers behind NGINX, shared JWT/data/quotas, unique worker IDs |

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

The same URL serves the frontend. See [frontend.md](docs/frontend.md) for screens and session behavior.

For the independent three-process setup, first configure the shared ignored signing-key file as described in [benchmarks/README.md](benchmarks/README.md), then:

```sh
./mvnw -B -ntp package
docker compose -f compose.yaml -f compose.scale.yaml build app-1
docker compose -f compose.yaml -f compose.scale.yaml up -d app-1 app-2 app-3 gateway
```

Open `http://127.0.0.1:8090`. Direct replica ports are 8081/8082/8083, with worker IDs 20/21/22. The native app may run separately with worker 0. Configure staff emails identically across replicas. [demo.md](docs/demo.md) documents local demo accounts and the walkthrough.

For the persistent Indian-style dataset:

```sh
RUN_DEMO_SEED=true ./mvnw -B -ntp -Dtest=DemoDataSeedTests test
```

This adds six staff and 50 customers with linked CRM records, preserving existing accounts. All new demo accounts use `demo-password-123`. Example staff: `ananya.iyer@demo.relay.test`; customer: `aarav.mehta@demo.relay.test`. See [demo-data.md](docs/demo-data.md) for the staff list, rerun behavior, and local-only limits. Staff land on the customer directory; existing Raghu access remains available.

To stop infrastructure while keeping its data:

```sh
docker compose stop
```

## Customer workflow

| Method and path | Behavior |
| --- | --- |
| `POST /api/customers` | Register; returns ID, shard ID, profile, and version |
| `POST /api/auth/login` | Check email/password; return a 15-minute bearer JWT |
| `GET /api/customers/{id}` | Read own profile, or another customer's profile with staff scope |
| `PUT /api/customers/{id}` | Update that profile using the latest version |
| `GET /api/customers/search?prefix=ra` | Staff-only customer-name prefix search |
| `GET /api/customers?limit=20` | Staff-only account directory; cursor continuation with afterId |
| `GET /api/customers/suggestions?prefix=ra` | Staff-only autocomplete suggestions |
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

For staff access, configure exact registered emails with `CRM_STAFF_EMAILS` and log in again. Default staff access is empty. Profile responses expose `X-Profile-Source: mongodb` or `redis`. See [customer-search-cache.md](docs/customer-search-cache.md) for permissions, search initialization, cache consistency limits, and verification.

Staff-only leads/opportunities are available under `/api/customers/{customerId}/leads`. The opportunity is embedded in its lead so qualification is one atomic MongoDB write. See [sales-workflow.md](docs/sales-workflow.md) for the complete Postman sequence, stages, assignment, pagination, and test results.

Interaction history and sales activities are under `/api/customers/{customerId}/interactions` and `/activities`. Staff create/manage them; customers can read their own records. All record contents are customer-visible. See [interactions-activities.md](docs/interactions-activities.md) for the Postman sequence, access rules, pagination, and limits.

Support tickets are under `/api/customers/{customerId}/tickets`; staff can query `/api/support/tickets`. Customers create/reply/read their own conversations; staff advance status and assign responsibility. Inboxes are under `/api/customers/{customerId}/notifications` and accessible only to their recipient. [support-notifications.md](docs/support-notifications.md) specifies the durable outbox and API.

Staff-only component metrics are exposed at `/actuator/metrics`. Health/liveness remains public; other management endpoints are denied.

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

Latest full integration-enabled run: **85 tests discovered, 82 passed, 3 opt-in tests skipped, 0 failures/errors**. Opt-in skips are the deliberately stopped-shard test and the benchmark/demo fixture tools. The stopped-shard test passed separately; fixture tools ran explicitly for their datasets. Two JavaScript helper tests and three Python harness tests also passed. Controlled API service failures are measured by the benchmark runner.

Selected observed checks:

- 100,000 routing keys: shard shares 33.09%, 33.81%, 33.10%.
- Adding a fourth ring node: 27.50% of keys remapped, compared with 75.01% for the tested modulo baseline. This test does not migrate documents.
- Shared limiter: 100 calls across 20 threads and 2 limiter objects, allowance 25, exactly 25 admitted and 75 rejected.
- Two independent HTTP server contexts on different ports shared all three rate-limit policies. Both ran in one JVM, so this is a functional distribution check, not a multi-process capacity benchmark.
- Real registration/login/profile requests, cross-customer access rejection, stale-update rejection, JWT validation, and shard health checks passed.
- Selective name-prefix query over 600 fixtures: 12 documents examined through the index versus 602 through the collection-scan baseline. Cache and staff-access checks passed; detailed results are in [customer-search-cache.md](docs/customer-search-cache.md).
- 32 concurrent lead qualifications: 1 success, 31 conflicts, exactly 1 opportunity. Three-shard lead colocation, representative distribution, and exact decimal storage checks passed; see [sales-workflow.md](docs/sales-workflow.md).
- 32 concurrent interaction inserts: all 32 persisted with distinct IDs. Of 32 concurrent task closures, exactly 1 succeeded and 31 conflicted. Owner/staff authorization, tied-timestamp history pagination, and three-shard engagement colocation passed; see [interactions-activities.md](docs/interactions-activities.md).
- 32 concurrent support replies: 1 success, 31 conflicts, exactly 1 new reply event. Outbox interruption/replay tests preserved event uniqueness and read acknowledgement; see [support-notifications.md](docs/support-notifications.md).

## Performance, architecture, and report

- [Architecture diagram and requirement/component mapping](docs/architecture.md)
- [Measured performance, resources, distribution, growth, and failures](docs/performance.md)
- [Reproducible JMeter experiment](benchmarks/README.md) and [retained results](benchmarks/results/)
- [Project report](docs/report.md) and [end-to-end demonstration](docs/demo.md)
- [Requirement-by-requirement completion audit](docs/requirements-checklist.md)

The server-count experiment uses one versus three independent JVM/container processes at 5/20/60 concurrent users, with two trials per configuration. It also expands synthetic customers from 200 to 3,000 and tests application/shard/Redis failures. Read the measured report for the actual results and experimental limitations.

## Remaining work and limits

- Replication/failover and safe data migration when changing shard membership
- Registration/email-index crash recovery, creation-request idempotency, and stricter cache consistency
- JWT revocation/refresh, broader operational/security hardening, backups, and independent-machine capacity tests

Redis and each MongoDB shard are currently single points of failure. Registration spans Redis and MongoDB without a distributed transaction, so crashes or ambiguous writes can leave the email index inconsistent. Profile caching is eventually consistent. The limiter returns 503 when Redis quota checking fails. JWT refresh/revocation, full staff role management, HTTPS termination, and production operational hardening are not implemented yet.

The measured experiment is on one shared laptop. Its server-count baseline uses the same sharding/cache design, and its closed-loop tests are short. These results do not establish production readiness or million-user capacity.
