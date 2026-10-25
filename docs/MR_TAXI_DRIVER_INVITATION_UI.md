# Driver invitation UI

Backend baseline: 694 passing tests confirmed locally by the user. This patch changes
frontend files only plus this document; it does not add backend tests or migrations.

VITE_DRIVER_INVITATIONS_ENABLED defaults false. Only an explicit build-time value
of true shows invitation buttons and registers /driver/activate. No environment file
is changed. Backend issuance, activation, encryption and sending flags/configuration
remain independent and must be configured deliberately before an end-to-end test.

Partners see the invitation button on active driver rows in compact and desktop
layouts. A confirmation shows the stored destination and replacement behavior.
The server still decides company eligibility, account linkage, cooldown and quotas;
the current driver DTO does not expose linkage, so a linked driver can show a button
but is rejected by the server. Success says queued, not delivered. No automatic
invitation retry or authenticated refresh replay is performed.

Configure the SMS adapter's activation page as the frontend HTTPS /driver/activate
page when a controlled sending test is authorized. Its #invite= fragment is captured
before React mounts and removed from the address bar. Tokens remain in memory only;
reloading the clean page requires reopening the original SMS. Duplicate/malformed
fragments are rejected. Activation submits token/password as an anonymous JSON body,
with no session refresh, no secret storage, no raw error rendering and no auto-login.
The form enforces password confirmation and the backend's 8-character / 72-byte
UTF-8 boundary. On success it clears credentials and links to normal login.

DRIVER is added to frontend role types and gets a protected basic /driver account
landing with sign-out. This is not a driver trip dashboard; trip/availability controls
remain future work. Existing customer/partner/admin destinations are unchanged.
Labels and feedback are translated into English and Albanian.

Validation here: six Node tests pass for fragment parsing, duplicate/malformed
credentials, password byte boundaries and default-disabled behavior. Patch application
and whitespace checked. Build/lint could not run: dependencies are unavailable and
npm offline installation failed on uncached packages. Browser tests and visual review
remain pending. On the user's installed frontend, run:

    node --test tests/driverInvitation.test.mjs
    npm run build
    npm run lint

Use Node 22.18+ or Node 24 for native TypeScript stripping in the new Node test.
Before merge, test on a phone-sized and desktop viewport with mock API responses:
confirmation/cancel, 202 queued, eligibility refusal, unavailable API, invalid link,
password mismatch, UTF-8 overflow, successful 204 and separate DRIVER login/sign-out.
Do not send live messages just to verify the UI. No commit, push or rollout included.
