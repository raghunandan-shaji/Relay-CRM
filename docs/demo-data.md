# Persistent Indian-style demonstration data

The opt-in seeder creates six synthetic staff accounts and 50 synthetic customers with Indian-style names, fictional company profiles, INR opportunities, communication history, sales activities, support conversations, and in-app notifications. Existing accounts and records are preserved, including Raghu and the earlier Rahul demonstration account.

All new accounts use the local demonstration password `demo-password-123`. Emails end in `@demo.relay.test`. Phone values beginning `+91 00000` are placeholders; no email, SMS, or call is sent.

| Staff name | Email |
| --- | --- |
| Ananya Iyer | ananya.iyer@demo.relay.test |
| Arjun Nair | arjun.nair@demo.relay.test |
| Kavya Menon | kavya.menon@demo.relay.test |
| Rohan Kulkarni | rohan.kulkarni@demo.relay.test |
| Neha Sharma | neha.sharma@demo.relay.test |
| Vikram Rao | vikram.rao@demo.relay.test |

Suggested customer login: `aarav.mehta@demo.relay.test`. Other examples include `diya.nair@demo.relay.test`, `meera.iyer@demo.relay.test`, and `karthik.subramanian@demo.relay.test`. The complete list is generated in the ignored `.local/demo-accounts.json`.

Staff see the customer directory after login. Browse pages with **Load more**, open an account, and use its Pipeline, History, Activities, and Support tabs. Customers see their own profile/history/activities/tickets/inbox and cannot access the staff directory.

## Reproduce the data

Start MongoDB/Redis and run:

```sh
RUN_DEMO_SEED=true ./mvnw -B -ntp -Dtest=DemoDataSeedTests test
```

The seeder uses worker ID 91, distinct from app workers 20/21/22 and benchmark worker 90. A local file lock prevents two seed invocations running together. Account documents are marked `demoSeed: relay-demo-v1`; the tool refuses to take over an existing unmarked account with the same email. Email reservations use the existing shared Redis index. An interrupted account reservation can require investigation rather than automatic overwriting.

Related records are created through the existing business services and outbox. Existing seeded records are recognized by their customer-scoped immutable title/summary/subject and skipped, so a successful rerun does not reset passwords, profile edits, pipeline stages, task outcomes, or ticket conversations. These are persistent demo fixtures, separate from the temporary performance dataset and its cleanup tool. This seeder is not an application HTTP endpoint.

The whole dataset is not one transaction. An interrupted seed can leave a partially initialized record; existing records are preserved rather than having status changes replayed over subsequent user edits.

New data is intentionally bounded: 100 leads, 62 opportunities, 200 interactions, 100 activities, and 63 tickets on the initial run, plus their conversation/notification events. Later user-created records can increase these counts. The actual verification summary and shard distribution are recorded after execution.

Observed initial run: 6 staff, 50 customers, the record counts above, and 404 customer events (conversation entries and notifications share the event store). Account distribution was shard-0: 18, shard-1: 21, shard-2: 17. The data was created with INR opportunity values and varied pipeline/task/ticket statuses.

A second execution preserved all 56 account IDs and all entity counts. Live checks found 59 registered accounts in pages of 20/20/19, seven eligible staff representatives including Raghu, and populated Aarav workflows: two leads, four interactions, two activities, two tickets, and ten inbox notifications. New staff/customer logins succeeded and the ordinary customer's directory request returned 403. [Verification summary](demo-data-verification.json).

## Staff inboxes

The seeder now also adds three `STAFF_BRIEFING` messages to each registered staff account, including the existing Raghu account: 21 staff messages in the verified setup. They contain a workspace briefing, a customer follow-up, and a support handover. **Open customer** opens the linked account. The briefings live in the staff recipient's own shard and inbox; staff are not given access to someone else's inbox.

Real login/inbox checks verified all seven staff accounts. A repeated seed created no duplicate briefings and preserved a message's read acknowledgement. Cross-staff inbox access returned 403. [Staff inbox verification](staff-inbox-verification.json).

These are persistent demo briefings. The current runtime outbox generates customer ticket/activity notifications; automatic future staff assignment alerts are not implemented.

## Staff configuration

The local scale configuration defaults to Raghu plus the six approved demo staff emails. Override `CRM_STAFF_EMAILS` to provide a different list. For native IntelliJ/Maven runs, use the same list in the ignored `.local/staff.properties`. Staff access is determined by the server, not a registration form.

These shared demo credentials and the email allowlist are only suitable for the loopback laboratory configuration. Trusted staff provisioning, email verification, and public-deployment hardening remain separate work.
