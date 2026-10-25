# Relay CRM — UCS3513 System Design Laboratory

## Objective and implemented scope

Build a working CRM prototype whose system components can be inspected and experimentally evaluated. The implementation supports customer registration/authentication, profile management, staff search/autocomplete, leads/opportunities and representative assignment, interaction history, sales activities, customer support, and durable in-app notifications. A basic role-aware frontend demonstrates these workflows.

The prototype is intended for a controlled laboratory. It demonstrates horizontal application process scaling and customer-partitioned storage. It does not claim unlimited scaling, database failover, or production readiness.

## Requirements and component selection

Functional requirements are implemented as bounded HTTP APIs with customer/staff access checks. Customers own their profile, ticket conversations, interaction/activity reads, and inbox. Staff search/manage CRM records across customers; owner-only profile editing and inbox acknowledgement remain restricted.

The selected system-design components are a Snowflake unique ID generator, consistent hashing, application-level database sharding, Redis key-value storage, a shared atomic rate limiter, and indexed autocomplete. [Architecture and component mapping](architecture.md) explains each mapping and why URL shortening and crawling are unnecessary for this scope. Component reuse avoids adding independent microservices where a shared library or store is sufficient.

## Architecture and algorithms

The frontend is served by the same Spring Boot processes as the API. NGINX distributes requests over independent containers, with shared JWT signing/configuration and distinct ID-generator worker IDs. Three standalone MongoDB databases store customers and their related data using customer-ID routing. Redis supplies global email lookup/reservation, quota state, and the shared profile cache.

Algorithms and consistency choices:

- Snowflake uses 41 timestamp bits, 10 worker bits, and 12 sequence bits after a January 2026 epoch; clock rollback is rejected. It is not a globally gapless counter.
- Consistent hashing uses SHA-256-derived unsigned 64-bit positions and 256 virtual nodes per shard. Related records route using the customer ID. Representative assignment reuses a ring over registered staff accounts.
- The fixed-window limiter uses an atomic Redis Lua operation with TTL. All replicas see the same quotas; boundary bursts and Redis failure remain limits.
- Profile caching uses TTL and version-aware writes. Fencing only lasts while the cache entry exists, so strict cache consistency is not claimed.
- Search normalizes names and uses a bounded prefix-range compound index on each shard, then merges results. Autocomplete returns the top bounded name matches.
- Profile, sales, activity, and ticket mutations use expected versions. Opportunity qualification is one atomic update of a lead with one embedded opportunity.
- Communication and support histories are separate indexed records with cursor pagination. Tickets and activities contain a single pending event rather than an unbounded embedded history.
- A MongoDB outbox saves a pending event with the business mutation. Retrying workers insert it once in the event journal/inbox by unique event ID, then conditionally clear the pending slot. Processing is at least once; stored duplicates are suppressed.

Detailed specifications: [authentication](authentication.md), [rate limiter](rate-limiting.md), [search/cache](customer-search-cache.md), [sales](sales-workflow.md), [interactions/activities](interactions-activities.md), [support/notifications](support-notifications.md), and [frontend](frontend.md).

## Correctness, security, and failure tests

The latest full backend suite ran 85 tests: 82 passed, 0 failures/errors, and three opt-in tests were skipped (the deliberately stopped-shard scenario and the benchmark/demo fixture tools). The stopped-shard regression test passed separately, with the shard restored afterward. Fixture tooling ran explicitly for its datasets. Two JavaScript display/session-helper tests and three Python harness/analysis tests passed separately. The benchmark stops live services and records their API behavior. The new staff directory is tested over real HTTP for access control, projection, validation, and cursor pagination.

Tests cover JWT validation and owner/staff permissions, registration uniqueness, profile version/cache behavior, literal/normalized prefix search, autocomplete accuracy, exact decimal opportunity storage, shared quotas across server contexts, concurrent ID generation, and physical colocation across three shards. Thirty-two competing qualification, task-completion, or ticket-reply updates produce one success and 31 conflicts. Outbox tests simulate interruption before delivery and replay after insertion; the stored event remains unique and read acknowledgement survives replay.

Browser testing exercised real persisted customer/staff workflows. User-entered data is rendered as text; tokens stay in memory; server authorization enforces access regardless of hidden frontend controls. A restrictive Content Security Policy permits only local resources. These checks are not a comprehensive security audit or penetration test.

## Performance evaluation

[Measured performance](performance.md) contains the generated tables/graphs and complete methodology. The experiment compares one active application process with three at 5, 20, and 60 concurrent JMeter users, twice per configuration. It reports mean/P95 response time, throughput, success/error rates, Docker CPU/memory samples, request distribution, and component timer means.

The request mix includes profile, search, autocomplete, interaction history, support listing, and interaction writes. A separate growth case expands synthetic customers from 200 to 3,000. Application-server removal during a workload, a stopped MongoDB shard, and Redis unavailability test degradation and recovery. The report retains raw compressed samples and explicitly separates successful service isolation from missing data-store failover.

The server-count baseline uses the same sharding/cache design. Indexed search is additionally compared with a forced collection scan. Hash-ring membership changes are compared with modulo routing. These comparisons answer different questions and must not be combined into a claim that the complete system outperforms an unimplemented alternative CRM.

## Trade-offs, constraints, and bottlenecks

Application replication removes per-process session state as a routing constraint. It adds database connection pools and background workers, and shares the same Redis/data-shard capacity. Extra replicas may help or hurt on a shared laptop. The measured report identifies observed scaling behavior and labels proposed bottleneck explanations as inferences rather than profiling results.

Customer colocation avoids cross-shard CRM mutations, but one large/hot customer's load cannot be split across shards. Name search and the staff support queue fan out to every shard and fail if a shard is unavailable. Changing the hash ring without migrating data is unsafe; ring movement measurements are not data migration.

Registration spans Redis reservation and MongoDB insertion without a distributed transaction, so an ambiguous crash/write can leave inconsistent email lookup state. Profile caches are eventually consistent. JWTs cannot be revoked before expiry; no refresh token or password-reset workflow is included. Retrying a creation POST after an ambiguous outcome can create another record; idempotency keys are not implemented.

There is no email verification. Staff accounts must be provisioned by a trusted operator before exposing registration; an untrusted claimant of an unregistered allowlisted email would otherwise receive staff scope. The lab binds to loopback and is not suitable for public sign-up as configured.

The bounded outbox makes changes and pending events atomic on standalone MongoDB, but a delivery failure can block that record's next mutation. Scheduled workers create a brief inbox delay and additional database work. Redis/MongoDB persistence does not replace replicated storage, backups, or tested disaster recovery.

## Feasibility and limits

The local demonstration requires Java 21, Docker Compose, MongoDB/Redis containers, JMeter, Git, and a browser. Python 3 produces benchmark summaries; Matplotlib produces plots. No paid cloud service is necessary. The main practical constraint is shared CPU/memory between servers, databases, and the load generator. Rebuilding/downloading images and running all trials takes appreciably longer than starting one Spring Boot process.

Verdict: feasible as a demonstrated laboratory prototype with the documented limits. A public or multi-machine deployment requires further infrastructure/security/operations work. Replicated shards/Redis, safe data migration, centralized ID-worker allocation, strict registration recovery, and sustained independent-load-generator testing remain outside the current completed milestone.

## Demonstration and reproduction

Follow [demo.md](demo.md) for the customer/staff walkthrough and [benchmark instructions](../benchmarks/README.md) to reproduce experiments. The code, source-controlled configuration, tests, report, measured tables, and figures together provide the project deliverables. The report must be presented with its limitations, especially the absence of automatic data-store failover and shard migration.

[requirements-checklist.md](requirements-checklist.md) audits deliverables 5–10 and separates implemented requirements from pending formal submission/live assessment. [demo-data.md](demo-data.md) describes the persistent Indian-style dataset and the browsable account directory. Historical capacity measurements predate those additions; their original artifact hash and raw data are retained.

For a concise overview, see [short-report.md](short-report.md). The current demo also includes recipient-owned staff inbox briefings; these are seeded messages, not automatic future staff alerts.
