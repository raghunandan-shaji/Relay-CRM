# Customer search, autocomplete, and profile cache

## Implemented behavior

Customer search and autocomplete use bounded prefix queries against a compound MongoDB index on every configured shard. Profile reads use a shared Redis cache containing only `CustomerResponse` data. Authentication always reads password hashes from MongoDB, never from this cache.

These components map to the project requirements as follows:

| Requirement | Component and reason |
| --- | --- |
| Customer lookup across shards | Indexed, normalized name-prefix query per shard; globally merge results |
| Suggestions while typing | Same prefix index, returning only customer ID and full name |
| Efficient repeated profile lookup | Redis key-value cache shared by application replicas |
| Safe customer-data access | Server-controlled staff scope; ordinary customers keep own-profile access |

## What to observe in Postman

The existing local demo account is designated as staff in the ignored `.local/staff.properties` file. No new account was created for that designation.

1. Log in again with `POST http://127.0.0.1:8080/api/auth/login`:

   ```json
   {"email":"raghu.demo@crm.local","password":"demo-password-123"}
   ```

   Use the new `accessToken` as a Bearer Token. Old tokens do not acquire staff access automatically.

2. Search customer names:

   ```text
   GET http://127.0.0.1:8080/api/customers/search?prefix=ra&limit=20
   ```

   Expected: 200 with matching customer summaries (ID, full name, email, company, shard ID). Results depend on the records currently stored. Names must start with the normalized prefix; this is not substring/fuzzy search.

3. Request autocomplete suggestions:

   ```text
   GET http://127.0.0.1:8080/api/customers/suggestions?prefix=ra&limit=10
   ```

   Expected: 200 with IDs and full names only. A UI can call this as its input changes; no UI is implemented in this stage.

4. Copy a returned ID and request `GET /api/customers/{id}`. Inspect response Headers. A cold read returns `X-Profile-Source: mongodb`; a repeat within the cache TTL returns `X-Profile-Source: redis`. If already cached, both reads can show Redis. Wait for the 60-second TTL to expire to observe another cold read.

5. For an update, log in as the profile's owner and use its latest version. `PUT /api/customers/{id}` updates the normalized search field and writes the new public profile version to Redis. The following GET returns the new data. Staff read access does not grant permission to update another customer's profile.

6. An ordinary customer's token gets 403 on search/suggestions. No token gets 401. A profile cached by staff remains inaccessible to other ordinary customers.

Authenticated calls still consume the shared API rate-limit allowance.

## Staff access

Set `CRM_STAFF_EMAILS` to a comma-separated list of exact registered email addresses. Matching is trimmed and case-normalized. The default is empty; public registration has no role/privilege field. A supplied `role: STAFF` does not grant staff access.

For local development, an ignored `.local/staff.properties` file can hold:

```properties
CRM_STAFF_EMAILS=your-staff-account@example.com
```

All replicas must use the same allowlist. A fresh login is required after changing it. Existing signed tokens retain their permissions until expiry, since immediate token revocation is not implemented. The repository does not contain the local account designation.

## Search algorithm and data migration

- Customer documents now have an additional `nameKey` field: Unicode NFKC normalization, surrounding whitespace removal, and locale-independent lowercase of `fullName`.
- New registrations and profile updates write that field with the original name. There is no second asynchronous search index to reconcile.
- On normal startup, an idempotent initializer creates `customer_name_prefix` on `(nameKey, _id)` with simple binary collation on each MongoDB shard. It streams older documents missing `nameKey` in batches of 200 and fills only the derived field. IDs, passwords, timestamps, and versions are preserved. A concurrent name update is protected by conditions on the old name and missing field.
- Search uses a lower bound on the normalized prefix and an exclusive Unicode successor as upper bound. User input is not interpreted as a regular expression. Prefixes require at least 2 characters; the maximum request length is 120; the result limit is 1–50.
- Each shard query sorts by `(nameKey, _id)` and returns at most the requested limit. The service merges in the same binary Unicode order with ID as tie-breaker, then truncates to the global limit. Memory and network results are bounded by shard count × limit, not total customer count.
- Queries have a 2-second server execution limit per shard and explicitly use the prefix index. Shards are queried sequentially. If a shard query fails, the request fails rather than returning an unmarked incomplete answer.

For a large existing collection, move the startup migration to a controlled offline job before deployment. Startup currently requires all shards during index initialization. Tests disable that initializer by default and invoke it explicitly where required. `SEARCH_INITIALIZE=false` skips initialization; search still requires the index and derived fields to have been prepared.

## Cache algorithm and consistency limits

Profile-cache keys default to `crm:profile:v1:{customerId}`. Each Redis hash stores the public response JSON and its version, with a 60-second TTL (`CUSTOMER_CACHE_TTL` overrides it).

On a cache miss, the service reads only the routed MongoDB shard and populates Redis. A hit avoids the MongoDB profile lookup. Successful updates write the new version to the shared cache. An atomic Lua comparison rejects older versions while the entry remains present, including delayed cache fills from earlier reads. Decimal version strings are compared by length and lexical order to avoid loss of precision for Java long versions.

The cache is an optimization, not the source of truth. Cache read/write failures do not roll back committed MongoDB profile updates. Normal stale values expire with the TTL, but eviction/expiry and a delayed older fill can bypass a vanished version fence. Failed cache updates can also leave stale data until expiry. This is eventual consistency, not a linearizable cross-database transaction. Strict guarantees would require stronger coordinated invalidation/version storage. Direct changes in Compass bypass application cache maintenance and may not appear until cache expiry.

The separate rate limiter still requires Redis for admitted HTTP requests, so cache fallback alone does not make the whole API independent of Redis during outages.

## Metrics

- `crm.customer_search.lookup`: search duration including shard queries and merging.
- `crm.customer_search.requests`: success/error count.
- `crm.customer_autocomplete.lookup`: suggestion duration.
- `crm.customer_cache.lookup`: Redis cache-read duration.
- `crm.customer_cache.requests`: hit/miss/invalid/unavailable counts.
- `crm.customer_cache.writes`: stored/older_rejected/unavailable counts.

Metrics do not include customer IDs, emails, or prefixes as tags. Search result DTOs and cached profiles exclude password hashes. Meters are inspected in tests; there is no public metrics endpoint.

## Observed verification on 25 October 2026

Full integration-enabled regression run: **60 discovered, 59 passed, 1 deliberate shard-outage scenario skipped, 0 failures/errors**.

| Check | Observed result |
| --- | --- |
| Selective prefix over 600 temporary fixtures on 3 shards | Index examined 12 documents total |
| Same query with forced collection scan | Examined 602 documents including 2 existing local records |
| Globally sorted top 5 from 8 matching name fixtures | All 5 IDs exactly matched the reference result |
| Autocomplete on the same fixtures | Same expected top-5 IDs; this is a small correctness sample, not a population accuracy study |
| Legacy field backfill | Derived key restored; ID unchanged; initializer safely repeated |
| Profile rename | Old prefix no longer matches; new prefix returns the same ID |
| Cache read path | MongoDB on the first read, Redis on the next |
| Newer cache version followed by a delayed old write | Newer version retained |
| Shared cache instance | Second cache object reads the same updated profile |
| Cache expiry | Entry becomes a miss |
| Real HTTP staff search/suggestions | 200 |
| Ordinary account search or foreign cached profile | 403 |
| Registration with client-supplied STAFF role | No staff scope granted |

These measurements establish index selectivity and functional correctness. They do not establish end-to-end latency or throughput at three workloads; that full-system evaluation remains pending. Search latency increases with shard fan-out; sequential shard queries, connection-pool limits, and Redis capacity are candidates to profile in that evaluation.

Run:

```sh
./mvnw test
RUN_INTEGRATION_TESTS=true ./mvnw test
```

Real-data tests delete only their temporary records and keys. The initializer's additive backfill and indexes remain in the local databases for the application to use.

Official references: [MongoDB index sorting](https://www.mongodb.com/docs/manual/tutorial/sort-results-with-indexes/), [Spring Data MongoDB index creation](https://docs.spring.io/spring-data/mongodb/reference/5.1/mongodb/mapping/mapping-index-management.html), [Spring Boot JSON configuration](https://docs.spring.io/spring-boot/4.1/reference/features/json.html).
