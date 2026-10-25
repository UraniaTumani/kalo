# Single-use invitation redemption foundation — pending local verification

This intermediate step creates and links a PENDING User(DRIVER), not a fully
activated account. No public endpoint, token response, real sending or login
activation is enabled. app.driver-invitation.redemption-enabled defaults to false.
The existing PENDING DRIVER login guard remains in force. Final ACTIVE state and
phone-verification semantics require binding-aware authentication checks first.
No real user flow should invoke this intermediate service before those gates.

The internal method accepts only token and password; driver/company/account IDs
and phone come from locked server-side records. It validates token format, hashes
it for lookup, and follows company -> driver -> queue -> invitation lock order.
Locked entities are refreshed to avoid stale caller persistence-context data.
A redeemable invitation, recorded issuer, delivery destination snapshot matching
the current driver phone, active/approved company, active/unlinked driver, and no
existing account with that phone are required. Historical unattributed invitations
or invitations without a delivery snapshot fail closed.

User creation, optional driver link, redeemed_at and pending-queue cleanup share
one transaction. Existing CUSTOMER/PARTNER/DRIVER accounts are never promoted or
linked by phone matching. Concurrent registrations are additionally protected by
the existing user-phone UNIQUE constraint; a collision rolls back redemption.
Tokens cannot be reused. Invalid invitation paths expose the same generic error.
Provider acceptance is not required as proof of handset delivery: possession of
the bearer token is evaluated internally, without changing phoneVerified=false.

Password validation requires at least eight characters and at most 72 UTF-8 bytes,
matching the configured BCrypt encoder's byte limit without silent truncation.
No plaintext password or token is persisted, logged or returned. Only the created
user ID is returned internally. A future public API still requires request limits,
generic responses, timing/abuse review and negative authorization tests.

Seventeen new tests cover single use, expiry/revocation, phone/account conflicts,
changed company, existing driver linkage, concurrency, atomic rollback, password
hashing and the actual login endpoint rejecting the resulting PENDING account.
Expected complete suite: 636 tests; no migration, 37 changesets remain.
Compilation/tests are pending local execution. Prior user-confirmed suite:
619 passed, 0 failures/errors/skipped, BUILD SUCCESS on 2026-10-25.
Master checklist completion statuses remain unchanged.
