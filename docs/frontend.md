# Basic frontend

The frontend is served by Spring Boot from `src/main/resources/static`. It uses plain HTML, CSS, and JavaScript modules. No Node package installation or separate frontend server is needed. Open the application's root URL: `http://127.0.0.1:8080` for a native run or `http://127.0.0.1:8090` for the three-process Docker setup.

Create an ordinary customer account or sign in with an existing account. Staff status is assigned only by the server's registered-email allowlist; registration cannot request a staff role. The client reads JWT scope only to decide which controls to display. Server-side authorization remains mandatory for every API call.

Implemented screens:

- Sign in and registration.
- Own profile editing and staff customer-name search.
- Staff lead creation, contact/loss status, opportunity qualification, and deal-stage progression.
- Communication history and staff interaction logging.
- Planned activities and staff completion/cancellation.
- Customer-owned tickets, conversation replies, staff queue/status/assignment.
- Own notification inbox and read acknowledgement.

Each list loads at most 20 records at a time and provides a cursor-based Load more button. It does not show fabricated global counts. Tokens stay in JavaScript memory, are not saved in browser storage, and expire after 15 minutes. Refreshing or signing out requires signing in again. Profile/ticket/activity updates include their loaded version, so stale actions can return 409 and require reloading.

User-supplied strings are inserted with DOM text nodes/textContent. They are not interpolated into HTML. Scripts/styles/assets are served locally under a restrictive Content Security Policy. Forms have labels, keyboard focus indicators, native input validation, live status feedback, and a responsive layout. There is no third-party font or image dependency.

The interface does not send real emails or perform calls. Inbox refresh is manual; automatic browser push and refresh tokens are not implemented. All interaction details, task descriptions/outcomes, and ticket messages are customer-visible. Staff assignment is responsibility tracking rather than a restriction on which staff member may act.

Run the small display/session-helper checks with `node --test benchmarks/frontend.test.mjs`. Backend integration tests separately enforce permissions and input validation. Browser checks and measured benchmark results are documented in the final report; syntax/unit checks alone must not be described as browser end-to-end testing.
