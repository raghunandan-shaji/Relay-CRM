# Assignment completion audit

This checklist distinguishes the stated laboratory requirements from stronger production capabilities that the brief does not explicitly require. It is based on the repository implementation, retained test results, and measured experiments, not an assumption that more replicas must improve throughput.

## Deliverables 5–10

| Deliverable | Status | Evidence / remaining action |
| --- | --- | --- |
| 5. Requirement analysis and component mapping | Implemented/documented | [Architecture](architecture.md) maps CRM workflows to six selected components and explains why URL shortening/crawling are unnecessary. |
| 6. System architecture and design | Implemented/documented | Architecture diagram, shard routing, shared Redis, independent app processes, outbox, algorithms, access rules, and trade-offs are documented. |
| 7. Working prototype and major workflows | Implemented/tested | Registration/login, profiles/search, leads/opportunities/assignment, history, activities, tickets, and notifications have APIs and a basic frontend. [Demo guide](demo.md), [seed data](demo-data.md), and automated/browser checks provide demonstrations. |
| 8. Quantitative performance evaluation | Measured/documented | [Performance report](performance.md): 5/20/60 users, two trials per one/three-server configuration, mean/P95 response time, throughput, success/errors, CPU/memory, component metrics, tables/graphs, and retained raw samples. Separate index/modulo baselines are documented. |
| 9. Scalability and failure analysis | Demonstrated/documented | Customer growth 200→3,000; live app addition/removal; even request distribution; customer/related-record shard distribution; shard and Redis outages/recovery; hash-ring membership/remapping tests; measured search behavior and explicit bottleneck hypotheses. |
| 10. Project report and demonstration | Report/guide ready; final submission and assessed demo pending | [Report](report.md) contains requirements, architecture, algorithms, implementation, results, limitations, and trade-offs. The student still needs to submit in the instructor's required format and perform the live demonstration. No required PDF/PPT format was supplied. |

## Functionality check

| Required workflow | Implemented behavior |
| --- | --- |
| Registration/authentication | BCrypt passwords, shared email lookup, signed expiring JWTs, common verification across replicas. |
| Customer profile management | Owner-only edits with expected versions; owner/staff reads and shared profile cache. |
| Customer search/autocomplete | Staff-only indexed name-prefix lookup across shards; bounded suggestions. |
| Lead/opportunity tracking | Existing-customer leads, expected-version updates, one embedded opportunity, INR/other currency values stored as decimal, forward stages. |
| Representative assignment | Configured registered staff, deterministic consistent-hash assignment, persisted responsibility. |
| Interaction/communication history | Separate customer-sharded records and event-time cursor pagination. Logging an email/call records it; it does not contact someone. |
| Sales activities | Planned tasks with due dates and representative, atomic completion/cancellation, outcomes and authorship. |
| Customer support | Owner ticket creation/replies/reads; staff queue, assignment, status changes, versioned conversations. |
| Customer notifications | Durable in-app inbox, atomic outbox save, replay/duplicate suppression, recipient-only acknowledgement. In-app delivery was explicitly selected. |
| Data partitioning | Customer, lead, interaction, activity, ticket, and event storage across three physical databases; related data colocated by customer ID. |
| Basic frontend | Customer/staff views, searchable/browsable accounts, forms, histories, pipeline, support, and inbox. |

## What is not a missing mandatory component

The prompt offers components for justified selection; it does not require every offered component. URL shortening and web crawling are optional and omitted with justification. Kafka is not used as a component. Redis supplies the key-value store rather than a new storage engine.

The failure/scalability requirement permits adding/removing application servers **or** data shards. Actual application process changes and failures were demonstrated. Shard-ring movement is simulated/measured, but migration of stored records is not implemented. Database replication, Redis failover, Kubernetes, cloud hosting, email delivery, and million-user tests are not explicitly required by this brief.

## Claims we can and cannot make

We can demonstrate a working CRM, independent application replicas, shared quotas/auth/cache, customer-sharded data, bounded indexed queries, concurrency protection, durable notification processing, and measured degradation/recovery.

We have **not** demonstrated a consistent throughput gain from adding application replicas on the shared laptop. The benchmark has substantial trial-to-trial variation, short warm-up, fixed execution order, and a colocated load generator. The report discloses those weaknesses. It is not evidence of million-user capacity, linear scaling, data-store high availability, or production readiness.

The retained capacity measurements predate the persistent Indian-style demo dataset and the new account-directory endpoint. Their exact artifact hash and dataset are retained. The directory is covered by real HTTP access/pagination tests and live seeded-data checks, not included in that historical timed request mix. The browser panel was unavailable for a fresh visual directory check; the earlier workflow browser checks remain documented separately.

## Final student actions

1. Review the report against any instructor-specific template/rubric that was not supplied here.
2. Rehearse the customer/staff workflows and the system explanation in [demo.md](demo.md).
3. Be able to explain the measured lack of consistent throughput improvement, Redis/shard failure behavior, version conflicts, outbox replay, and shard-migration limitation.
4. Submit the report/code/results and perform the assessed demonstration.

Verdict: the stated implementation and minimum experimental requirements are addressed. Formal submission, live assessment, and any undisclosed instructor-specific requirements are not completed by writing the code.
