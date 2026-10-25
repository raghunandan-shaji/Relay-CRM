# Relay CRM — short project report

**Course:** UCS3513 System Design Laboratory

**Date:** 25 October 2026

## Purpose

Relay CRM keeps customer information, sales enquiries, communication history, follow-up tasks, and support conversations together. Customers manage their own information and ask for help. Staff search customers and handle sales/support work. A plain HTML/CSS/JavaScript frontend is served by Spring Boot.

## Architecture and selected components

Three independent Java 21/Spring Boot processes run behind NGINX. They share a signing key, Redis, and three standalone MongoDB databases. Detailed diagrams are in [README.md](../README.md).

| Component | Job |
| --- | --- |
| Snowflake ID generator | Creates record IDs with distinct worker numbers on each server. |
| Consistent hashing | Routes account data to a shard and assigns eligible representatives. |
| Application-level sharding | Spreads customers and related records across three physical databases. |
| Redis key-value storage | Holds email lookups, request quotas, and cached public profiles. |
| Atomic rate limiter | Enforces shared allowances across application processes. |
| Indexed search/autocomplete | Finds names using bounded prefix queries. |

JWTs provide expiring login credentials. Owner/staff checks protect records. Expected versions prevent lost updates. Opportunities are embedded in leads for atomic qualification. Ticket/activity changes save a pending event in the same document; retrying workers publish it with duplicate suppression.

URL shortening and crawling were omitted because these workflows do not need them.

## Demo data and inbox behavior

The persistent dataset adds six staff and 50 Indian-named customers, with 100 leads, 62 opportunities, 200 interactions, 100 activities, and 63 tickets. Existing accounts are preserved. Registered demo staff, including Raghu, receive seeded workspace/customer/support briefings. These are practice messages; future staff alerts are not automatic.

Previously staff inboxes were empty because events were addressed to customers. An inbox shows only messages addressed to its signed-in account. Staff briefings preserve that access rule.

## Tests and measured results

The latest full backend suite recorded 82 passes, three opt-in skips, and no failures. The stopped-shard scenario and data tools ran separately. Two JavaScript and three Python checks passed. Integration tests cover permissions, pagination, version conflicts, quotas, shard colocation, and outbox replay. The staff seed is checked for safe reruns and verified through real login/inbox requests.

JMeter ran two trials per configuration at three concurrency levels. Pooled throughput was:

| Concurrent users | One server: requests/s | Three servers: requests/s |
| --- | --- | --- |
| 5 | 464.07 | 408.01 |
| 20 | 580.89 | 582.75 |
| 60 | 667.30 | 652.42 |

Normal capacity trials had no request errors. Removing an app during a workload caused one transient 502. A stopped shard left other-shard reads available but affected reads/global search failed. Redis loss caused authenticated APIs to return 503 while liveness remained available. Recovery was measured. CPU/memory, latency, distribution, raw samples, and graphs are in [performance.md](performance.md).

## Limits and conclusion

The prototype demonstrates working CRM flows, independent application processes, partitioned storage, concurrency protection, and measured failure behavior. More replicas did not consistently increase throughput on the shared laptop. Trials were short and variable, with the load generator on the same host.

Data-store failover, live shard migration, strict cross-store registration recovery, and public-deployment hardening remain unimplemented. Historical measurements predate the persistent demo data and directory. The implementation and minimum experimental requirements are addressed; final submission and the assessed demonstration remain student actions.
