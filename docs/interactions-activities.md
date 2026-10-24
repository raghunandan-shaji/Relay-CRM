# Interaction history and sales activities

## Scope and component mapping

Interactions record communications that already happened: CALL, EMAIL, MEETING, or NOTE. Activities record planned work: CALL, EMAIL, MEETING, or TASK. Logging an email or call does not send an email or place a call.

Both belong to an existing registered customer, and may optionally reference one of that customer's leads. They use separate `interactions` and `activities` collections in the customer's physical MongoDB shard. Growing histories therefore do not expand the customer document or require rewriting its full history.

| Requirement | Reused design/component |
| --- | --- |
| Increasing interaction and activity data | Customer-ID sharding and consistent hashing; each customer's records remain colocated |
| Unique record IDs across replicas | Existing Snowflake generator; a distinct worker ID is required per replica |
| Planned-work responsibility | Existing registered-staff representative pool and consistent-hash assignment, or explicit eligible representative |
| Chronological communication history | Compound `(customerId, occurredAt descending, _id descending)` index and bounded cursor pagination |
| Activity listing/filtering | `(customerId, _id)` and `(customerId, status, _id)` indexes, bounded cursor pagination |
| Concurrent task completion | Atomic MongoDB update matching customer ID, activity ID, OPEN status, and expected version |
| Access and traffic control | Existing JWT, owner/staff authorization, shared Redis limiter |

No dependencies were added. Redis does not cache these records; reads go to the authoritative customer shard.

## Access and data handling

Staff can create/read interaction records and create/read/close activities for any customer. Ordinary customers can only read their own records. They cannot create, change, or close them. Leads/opportunities retain their existing staff-only access rules.

**All interaction details, activity descriptions, and outcomes are visible to their customer.** These fields must not hold staff-only notes. There is no hidden/internal-note flag.

`recordedBy`, `createdBy`, and `closedBy` come from the authenticated staff token, not from client JSON. An optional `leadId` must resolve under the same customer, including when two customers share a shard. Missing or mismatched records return 404. Authorization is checked before records are read.

Interaction records have no update/delete API. `occurredAt` defaults to the server time and cannot be in the future; `recordedAt` records when the server accepted the entry. Dates are stored and returned at millisecond precision. This is an append-only API, not a tamper-proof audit system: a database administrator can still modify records.

Activities require a present/future `dueAt`, start OPEN at version 0, and can become COMPLETED or CANCELLED once. A successful close increments the version and records the actor/time/outcome. A stale write, second close, or reopening attempt returns 409. Assignment records responsibility; any staff member can close a task. Completing a task does not automatically append an interaction; record an actual communication separately.

## Postman demonstration

Use `http://127.0.0.1:8080` and a fresh staff bearer token from `POST /api/auth/login`. The existing local staff account is `raghu.demo@crm.local` / `demo-password-123`. Replace `{customerId}` with an existing customer ID, for example `107548804919590912` in the current local data.

1. Record a communication:

   ```text
   POST /api/customers/{customerId}/interactions
   ```

   ```json
   {
     "type": "CALL",
     "summary": "Discussed CRM requirements",
     "details": "Customer requested a product demonstration."
   }
   ```

   Expected: 201, an `id`, customer ID, event/recording timestamps, authenticated `recordedBy`, and `X-Data-Shard`. Optionally supply `leadId` and a past `occurredAt`.

2. Read the history or an individual entry:

   ```text
   GET /api/customers/{customerId}/interactions?limit=20
   GET /api/customers/{customerId}/interactions/{interactionId}
   ```

   History is ordered by event time descending, then string ID descending for ties. Continue with **both** `beforeAt` and `beforeId` from the previous page's last entry. Use Postman's Params table to encode the timestamp. Limit is 1–50. Missing one cursor field returns 400.

3. Create a follow-up:

   ```text
   POST /api/customers/{customerId}/activities
   ```

   ```json
   {
     "type": "MEETING",
     "title": "Product demonstration",
     "description": "Walk through the customer workflow.",
     "dueAt": "2026-10-26T10:00:00+05:30"
   }
   ```

   Choose a future date when rerunning this example. Expected: 201, OPEN, version 0, and an automatically selected staff `representativeId`. Optional `leadId` links the task; optional eligible `representativeId` selects its owner explicitly.

4. List open activities and complete one:

   ```text
   GET /api/customers/{customerId}/activities?status=OPEN&limit=20
   PUT /api/customers/{customerId}/activities/{activityId}/status
   ```

   ```json
   {"status":"COMPLETED","outcome":"Demonstration completed","version":0}
   ```

   Expected: 200, COMPLETED, version 1, `closedAt`, and `closedBy`. Repeating the same update returns 409. Use CANCELLED to cancel an open task. Activity listing uses ascending string-ID order, optional OPEN/COMPLETED/CANCELLED filtering, and `afterId` from the last result; it is not due-date ordering or a global task dashboard.

5. Log in as the customer whose ID is in the URL. Both GET lists and individual GETs should succeed. POST/PUT should return 403, and reading another customer's records should return 403.

In Compass, open the customer's shard and filter `crm.interactions` or `crm.activities` by `{ "customerId": "..." }`. The profile document contains neither history array.

## Verification and metrics

Run `RUN_INTEGRATION_TESTS=true ./mvnw test` with MongoDB/Redis running and the development app stopped. Fixtures are isolated by generated customer IDs and removed afterward. Tests cover real HTTP authorization/validation, event-time cursor ties, concurrent logging/completion, and physical colocation across all three shards.

Observed on 25 October 2026: the full integration-enabled suite discovered 77 tests, with 76 passed, 1 deliberate outage test skipped, and no failures/errors. All 7 new engagement tests passed.

| Scenario | Observed result |
| --- | --- |
| Concurrent interaction inserts | 32 attempted, 32 persisted, 32 distinct IDs |
| Concurrent completion of the same activity at version 0 | 1 success, 31 conflicts, stored version 1 |
| Physical colocation on shard-0, shard-1, shard-2 | Each fixture customer had 20 interactions and 10 activities on its own shard, zero on the other shards |
| Event-time pagination | 6 events, including 5 tied timestamps, retrieved in pages of 2 without duplicates or omissions |
| Backdated event | Sorted by occurredAt even though inserted last |
| Real HTTP permissions | Staff creation 201, completion 200; owner reads 200; owner writes and foreign customer reads 403; anonymous 401 |
| Authorship fields supplied by client | Ignored; stored actor matched the authenticated staff ID |
| Invalid cursor/date/payload/version and repeated/reopening updates | 400 or 409 as applicable |

These are correctness and distribution measurements, not throughput/latency benchmarks.

Metrics: `crm.engagement.created` tagged only by entity, `crm.interaction_timeline.lookup` duration, `crm.activity.changes` tagged by success/conflict, and `crm.activity.write` conditional-update duration. Invalid OPEN transitions occur before the write meters. These internal meters reuse Micrometer; no public metrics endpoint was added.

Startup creates indexes on all configured shards through the existing sales initializer. `SALES_INITIALIZE=false` is safe only after lead, interaction, and activity indexes have been provisioned. Queries use named indexes, a maximum page size of 50, and a two-second database execution limit.

## Limits and trade-offs

Cursor pages are live reads, not a snapshot: an interaction inserted into an already-read portion may require refreshing the history. String-ID ordering is deterministic but does not promise global creation-time order. Retrying a creation POST after an ambiguous network failure can create a duplicate because idempotency keys are not implemented.

One very large/hot customer still uses one shard. Changing shard membership without migrating stored data remains unsafe. Database replication, automatic failover, distributed transactions, retention, activity reassignment/rescheduling, reminders, and actual message delivery are not implemented here. There is no customer-deletion API, so creation's existence check is not racing an application-level deletion.

The implementation uses existing Java/Spring/MongoDB/Redis installations. No additional tools or paid services are required for this stage. It is a feasible local prototype; deploying it publicly still requires secured infrastructure and operational work. Correctness/concurrency tests do not substitute for the pending three-level capacity, resource, and failure/recovery benchmarks.
