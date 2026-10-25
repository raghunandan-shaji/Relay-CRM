# Support tickets and durable in-app notifications

## Access and workflow

Customers can create, list, read, and reply to their own tickets. Staff can handle every customer's tickets, read the combined support queue, assign a registered representative, and advance status. Inbox access and read acknowledgement belong exclusively to the recipient, including when another account is staff.

Ticket statuses advance OPEN → IN_PROGRESS → RESOLVED → CLOSED. Skipping forward is allowed; reopening is not. Replies are accepted until CLOSED. A reply or handling change requires the latest ticket version. A successful update increments it once; stale callers receive 409. Creation assigns an eligible registered staff representative using the existing consistent-hash assignment component.

All support messages are customer-visible. Attachments, private staff notes, escalation rules, reopening, and outbound email delivery are outside this prototype.

| Method and path | Access / purpose |
| --- | --- |
| POST `/api/customers/{customerId}/tickets` | Owner/staff; subject, message, priority |
| GET `/api/customers/{customerId}/tickets` | Owner/staff; optional status, afterId, limit |
| GET `/api/customers/{customerId}/tickets/{ticketId}` | Owner/staff; latest version/status |
| GET `/api/customers/{customerId}/tickets/{ticketId}/messages` | Owner/staff; afterId/limit pagination |
| POST `/api/customers/{customerId}/tickets/{ticketId}/messages` | Owner/staff; message and expected version |
| PUT `/api/customers/{customerId}/tickets/{ticketId}` | Staff; next status and/or eligible representativeId, expected version |
| GET `/api/support/tickets` | Staff; combined shard queue, optional status/afterId/limit |
| GET `/api/customers/{customerId}/notifications` | Recipient only; newest ID first, beforeId/limit pagination |
| PUT `/api/customers/{customerId}/notifications/{eventId}/read` | Recipient only; idempotent acknowledgement |

Limits are 1–50 entries per page, subject 160 characters, message 4,000 characters. Priorities: LOW, NORMAL, HIGH, URGENT. Queue ordering is ascending string ID; priority is recorded but does not reorder the queue. All global queue queries fail with 503 if a shard cannot be queried, rather than silently presenting an incomplete global list.

Example create body:

```json
{"subject":"Need help with my account","message":"Please help me update my details.","priority":"NORMAL"}
```

Example reply body:

```json
{"message":"We have updated the instructions.","version":0}
```

Example staff handling body:

```json
{"status":"IN_PROGRESS","version":1}
```

Use actual returned versions. The frontend includes these versions automatically. An outdated frontend action returns a conflict and requires reloading the record.

## Atomic outbox algorithm

Ticket and activity documents contain at most one pending event. The application saves the business change, expected-version increment, and pending event in **one MongoDB document write**. The pending field is excluded from public JSON responses.

1. Before another change to that record, deliver any existing pending event.
2. Match the expected version and absence of a pending event; atomically save the new state and event.
3. Insert that event into `customer_events` on the same customer shard. Its Snowflake ID is the unique `_id`.
4. Clear the source's pending field only if its event ID still matches the published event.
5. A worker in each application process scans sparse pending-event indexes every two seconds, up to 50 records per source collection and shard per pass. Failed data-store operations are retried on subsequent passes. One failed shard does not stop scans of the others.

A crash before step 3 leaves durable pending work. A crash after insertion but before clearing causes a replay: duplicate event insertion is recognized, preserving the existing event and any read acknowledgement. Multiple workers can process the same pending event safely. The worker does not increment the business version when clearing delivery state.

This is at-least-once processing with duplicate suppression in the stored inbox. It is not a claim of exactly-once network delivery. The single pending slot bounds aggregate size, but limits writes to one record when delivery is unavailable; data-store failures can block that record's next update. There is no indefinite array of undelivered messages embedded in the aggregate.

The `customer_events` collection serves both ticket conversation history and the customer inbox. Customer replies remain in conversation history without notifying the customer about their own reply. Ticket creation, staff replies/handling changes, and activity creation/completion/cancellation generate customer notifications. The frontend's inbox refresh is manual; there is no WebSocket or browser push service. Staff handle incoming requests through the support queue rather than a separate staff notification subscription.

## Verification

Real HTTP integration tests cover customer/staff access, wrong-customer rejection, ticket progression, version conflicts, conversation history, and idempotent read acknowledgement. Failure tests hold delivery after commit, start a new worker instance, and replay an already-inserted event after simulating an interrupted clear. The event is stored once and `readAt` remains intact. A 32-way concurrent reply test produces one success and 31 conflicts. Three-shard tests verify ticket and event colocation. Existing task-completion concurrency tests also run against the outbox-enabled implementation.

Metrics are available to staff at `/actuator/metrics`. `crm.outbox.delivery` counts delivery attempts tagged delivered/retry; repeated workers can count the same stored event more than once. It must not be interpreted as a count of unique inbox items.
