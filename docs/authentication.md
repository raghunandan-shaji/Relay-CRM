# Authentication stage

## Run

Start infrastructure with `docker compose up -d`, then run `./mvnw spring-boot:run` from the project root (or run `ScalableCrmApplication` in IntelliJ).

The application reads a locally generated signing secret from the ignored `.local/jwt.properties` file. Keep this file private. On another machine, set `JWT_SECRET` to Base64 encoding of at least 32 securely generated random bytes; `openssl rand -base64 32` generates a suitable value. Do not commit the value. A missing or short secret prevents startup.

The server listens at `http://127.0.0.1:8080` by default.

## What to observe in Postman

1. Registration: `POST /api/customers`, Body → raw → JSON:

   ```json
   {"fullName":"Demo Customer","email":"unique@example.com","password":"password-123"}
   ```

   Expected: 201 with customer `id` and `shardId`. Reusing an email returns 409.

2. `GET /api/customers/{id}` without Authorization: expected 401.

3. Login: `POST /api/auth/login`, Body → raw → JSON:

   ```json
   {"email":"unique@example.com","password":"password-123"}
   ```

   Expected: 200 with `accessToken`, `tokenType: Bearer`, `expiresIn: 900`, and `customerId`. Wrong credentials return a generic 401.

4. On the profile request, select Authorization → Bearer Token and paste `accessToken`. Repeat `GET /api/customers/{id}`: expected 200. Use the logged-in customer's ID.

5. Send `PUT /api/customers/{id}` with the same token:

   ```json
   {"fullName":"Updated Customer","phone":"9999999999","company":"CRM Lab","version":0}
   ```

   Expected: 200 and version 1. Use the latest version for subsequent updates; reusing a stale version returns 409.

6. Register a second customer. Use the first customer's token to GET or PUT the second customer's ID: expected 403. Tampering with a token returns 401.

Staff-only customer search is now implemented. The server's `CRM_STAFF_EMAILS` allowlist designates staff accounts; the default is empty. The local demo allowlist is stored outside Git in `.local/staff.properties`. A fresh login issues a `crm:staff` scope only for configured emails. Staff may search and read profiles, but cannot update another customer's profile. See [customer-search-cache.md](customer-search-cache.md).

The existing `raghu.demo@crm.local` account uses demo password `demo-password-123` and ID `107546705825628160` if it has not been deleted from the local databases.

## Tests

- `./mvnw test`: regression suite including signature/claim checks, ownership checks, validation, and password checks. Database-dependent scenarios are skipped by default.
- `RUN_INTEGRATION_TESTS=true ./mvnw test`: also runs real MongoDB/Redis tests and an HTTP registration/login/profile workflow. The deliberate stopped-shard test stays opt-in with `RUN_SHARD_FAILURE_TESTS=true`.

The HTTP integration test deletes only the temporary customer records and email-index keys it created.

## Scaling requirements and current limits

- Every application replica must use the same secret, issuer, audience, and shard map, and a distinct `ID_WORKER_ID`. Bearer validation uses no HTTP session or Redis lookup. Login still depends on Redis and the selected MongoDB shard.
- Access tokens expire after 15 minutes. Spring's default timestamp validator allows 60 seconds of clock skew. There is no refresh token, logout revocation, or password reset. Ordinary customers can only access their own profile; server-designated staff can also search and read other profiles. Removing an email from the allowlist affects new logins; existing staff tokens remain valid until expiry.
- Rotating the sole signing key invalidates existing tokens. Planned overlapping key rotation would require a key ring.
- The email index is currently authoritative in one Redis instance. A missing index breaks email login; there is no index-rebuild/reconciliation job yet. Redis and each MongoDB shard remain single points of failure.
- The registration operation spans Redis and MongoDB without a distributed transaction. A process crash or ambiguous database write can leave the email index inconsistent. This stage does not establish crash-safe account creation.
- Shared Redis rate limiting is implemented in the next stage; see [rate-limiting.md](rate-limiting.md). HTTPS termination is not implemented yet. Keep this build on localhost; do not expose it publicly.
- The hash-ring tests demonstrate key remapping; they do not migrate stored records. Do not change shard membership for populated data until migration is implemented.

Official reference used: [Spring Security JWT resource server](https://docs.spring.io/spring-security/reference/7.1/servlet/oauth2/resource-server/jwt.html).
