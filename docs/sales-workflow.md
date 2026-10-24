# Leads, opportunities, and representative assignment

## Scope and component mapping

Each lead belongs to an existing registered customer. It is stored in the MongoDB shard selected by that customer's ID, not by the lead's own ID. A lead can have one embedded opportunity.

| Requirement | Implemented component/design |
| --- | --- |
| Lead/opportunity IDs across application replicas | Existing Snowflake generator; distinct worker IDs remain required |
| Increasing customer and lead data | Existing consistent-hash customer routing; leads colocated with their customer |
| Automatic sales-representative assignment | Consistent-hash ring over registered staff account IDs, with 256 virtual nodes per representative |
| Safe qualification and opportunity creation | One atomic MongoDB document update with an expected version |
| Concurrent pipeline updates | Version checks and atomic conditional writes; stale callers receive 409 |
| Efficient lead listing | `(customerId, _id)` compound index and bounded cursor pagination |
| Staff access and traffic control | Existing JWT staff scope and shared Redis rate limiter |

No new dependencies were added. Staff accounts reuse the current registered-account model; there is no separate employee database or organization/tenant boundary yet.

## Access and stages

All sales endpoints require `crm:staff` in a valid bearer token. Ordinary customer tokens receive 403, including when requesting their own customer ID's internal pipeline. Anonymous requests receive 401. Any configured staff member can manage sales records; assignment indicates responsibility rather than an access restriction.

Lead statuses:

- NEW → CONTACTED or LOST.
- NEW or CONTACTED → QUALIFIED only through opportunity creation.
- QUALIFIED and LOST cannot be changed through the lead-status endpoint.

Opportunity stages are OPEN, PROPOSAL, NEGOTIATION, WON, and LOST. Moves must go forward, skipping intermediate stages is allowed, and WON/LOST are terminal. An opportunity may be lost from any open stage. Closed leads/deals cannot be reassigned.

All modifying requests after creation require the latest aggregate `version`. That single version covers lead status, representative assignment, and opportunity stage; each successful change increments it once. Missing versions are rejected.

## Representative pool

`GET /api/sales-representatives` lists registered accounts whose emails are in the server's `CRM_STAFF_EMAILS` allowlist. Accounts not yet registered are omitted. Supplying an ordinary customer ID as a representative returns 400. Automatic assignment without an eligible representative returns 503.

Omit `representativeId` when creating a lead to use automatic assignment, or supply an eligible ID explicitly. The ring is reused while pool membership is unchanged. Assignment is persisted, so adding/removing representatives does not silently rewrite existing leads. Existing assignments to a removed member require explicit reassignment.

The current local demo has one configured staff representative, so every automatic assignment goes to that account. The test suite separately verifies a three-member pool. This is deterministic key distribution, not workload-aware scheduling or tracking of employee availability.

## Postman demonstration

First log in again with the configured staff account and use the new `accessToken` as Bearer Token. The local demo credentials are `raghu.demo@crm.local` / `demo-password-123`. Tokens expire after 15 minutes.

Use `http://127.0.0.1:8080` as the base URL. Replace `{customerId}` with an existing customer's ID from search, and `{leadId}` with the ID from the lead-creation response.

1. View eligible representatives:

   ```text
   GET /api/sales-representatives
   ```

2. Create a lead:

   ```text
   POST /api/customers/{customerId}/leads
   ```

   ```json
   {"title":"CRM subscription enquiry","source":"Website","notes":"Demo lead"}
   ```

   Expected: 201, a Snowflake `id`, `status: NEW`, `representativeId`, `opportunity: null`, and `version: 0`. The `X-Data-Shard` response header identifies the physical shard.

3. Optional: mark the lead contacted:

   ```text
   PUT /api/customers/{customerId}/leads/{leadId}/status
   ```

   ```json
   {"status":"CONTACTED","version":0}
   ```

   Expected: 200 and version 1. Skip this if you want to qualify directly from NEW.

4. Create the opportunity and qualify the lead in one operation:

   ```text
   POST /api/customers/{customerId}/leads/{leadId}/opportunity
   ```

   ```json
   {"amount":50000.00,"currency":"INR","version":1}
   ```

   Use version 0 instead if you skipped the CONTACTED update. The response is the full lead, with `status: QUALIFIED`, a newly generated `opportunity.id`, and `opportunity.stage: OPEN`.

5. Move the opportunity forward using the newest response's version:

   ```text
   PUT /api/customers/{customerId}/leads/{leadId}/opportunity/stage
   ```

   ```json
   {"stage":"PROPOSAL","version":2}
   ```

   Use the version you actually received, not a fixed example value. Subsequent changes can move to NEGOTIATION, WON, or LOST. Reusing an old version returns 409.

6. Read the full record or list that customer's leads:

   ```text
   GET /api/customers/{customerId}/leads/{leadId}
   GET /api/customers/{customerId}/leads?limit=20
   ```

   Continue a list with `afterId={last ID from the previous page}`. Results use ascending string-ID order, not a guarantee of global creation-time order. Limit is 1–50. There is no expensive offset scan or total count.

7. To change responsibility on an open record:

   ```text
   PUT /api/customers/{customerId}/leads/{leadId}/representative
   ```

   ```json
   {"representativeId":"an-id-from-the-representatives-endpoint","version":2}
   ```

   Replace both example values. The response increments the aggregate version.

In MongoDB Compass, connect to the customer's shard (27017, 27018, or 27019), open `crm.leads`, and filter by `{ "customerId": "..." }`. The opportunity is inside its lead document; there is no separate opportunities collection.

## Correctness and failure behavior

- Amounts must be positive, with at most 12 integer digits and 2 fractional digits. Currency is required and checked against Java's currency registry. Values are stored as BSON Decimal128, avoiding binary floating-point storage. This records a quoted deal value; it does not implement billing, tax, exchange rates, or payment processing.
- A write matches the immutable customer/lead IDs and the expected version, increments the version, and returns the updated record. Concurrent writers cannot silently overwrite one another.
- Lead qualification sets the QUALIFIED status and complete opportunity subdocument in that same atomic write. A process failure during that write cannot leave a separately inserted orphan opportunity. Repeating an already successful qualification returns 409; the client should GET the lead after an ambiguous response to discover whether it succeeded.
- Each customer/lead route is checked with both IDs. A lead ID placed under the wrong customer returns 404 even if both customers happen to share a shard.
- Creation first verifies that the customer document exists in the authoritative shard. There is no customer-deletion API, native foreign key, or distributed transaction covering representative discovery and lead insertion.
- Missing records return 404, ineligible representatives/invalid payloads return 400, stale versions or invalid state transitions return 409, and data-store failures use the existing 503 handling.
- Malformed JSON, unsupported enum values, and invalid query-parameter types return a generic 400 without exposing raw request contents.
- Lead creation itself has no idempotency-key support. Retrying a POST after an ambiguous network failure can create another lead. Qualification is protected against creating a second opportunity by its version/state checks.

## Observed verification on 25 October 2026

| Scenario | Observed result |
| --- | --- |
| 10,000 synthetic lead keys over 3 representatives | 3,518 / 3,164 / 3,318 assignments (35.18% / 31.64% / 33.18%) |
| Assignment through 2 independent service objects | Same representative for every tested key |
| 32 concurrent qualification calls on one lead | 1 success, 31 conflicts, 1 embedded opportunity, aggregate version 1 |
| 3 physical shards, 1 test customer per shard | Each held exactly that customer's 10 leads and embedded opportunity; no matching leads on other shards |
| Cursor pagination | Two pages of 5 cover 10 distinct leads without duplicates |
| Real HTTP pipeline | Creation, contact, reassignment, qualification, proposal, negotiation, and won transitions passed |
| Exact amount check | Native BSON Decimal128 value matched 123456.78 |
| Stale/closed updates | 409 |
| Ordinary customer attempting sales writes | 403 |
| Missing customer / invalid currency / malformed input | 404 / 400 / 400 |

The full-system latency, throughput, error-rate, and resource benchmarks across three workload levels are still pending. Assignment distribution and atomicity tests do not establish overall system capacity.

Metrics currently record `crm.sales.created` for inserted leads, `crm.sales.changes` for successful/failed conditional writes, and `crm.sales.write` for that conditional-write duration. Preflight validation/state/version failures occur before those conditional-write meters; they are not included in those counts. No customer or lead IDs appear in metric tags.

Run `RUN_INTEGRATION_TESTS=true ./mvnw test` with infrastructure running and the development app stopped. Sales tests create and remove only their own fixtures. Startup creates `leads_by_customer` on all configured shards; set `SALES_INITIALIZE=false` only if those indexes have already been provisioned.

## Scaling limits

Colocation makes a customer's pipeline operations single-shard operations, but a very large/hot customer can still overload one shard. Cross-customer pipeline dashboards are not implemented. A global workload view would need bounded shard fan-out or an asynchronous reporting index.

Representative-pool discovery currently checks configured staff accounts per assignment, so its cost grows with staff count. MongoDB and Redis are still single instances without replica failover. These remain explicit limitations of this checkpoint.

Official atomicity reference: [MongoDB Atomicity and Transactions](https://www.mongodb.com/docs/manual/core/write-operations-atomicity/).
