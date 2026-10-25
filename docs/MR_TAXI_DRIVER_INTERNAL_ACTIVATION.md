# Internal driver activation — pending local verification

The new activate(token,password) method is transactional and separately guarded
by app.driver-invitation.activation-enabled, which defaults to false. No public
endpoint, scheduler change, credential or real SMS configuration is introduced.
The earlier redeemToPendingAccount method keeps its separate default-disabled
redemption flag and PENDING/unverified behavior.

Final activation uses the existing company -> driver -> queue -> invitation lock
order, refreshed rows, current eligibility and matching destination snapshot.
It additionally requires ACCEPTED or REVIEW delivery state and the exact expected
provider message ID for this invitation, destination and token hash. An unsent,
failed, cancelled or mismatched delivery cannot verify the account. Only the valid
unused bearer token can consume the invitation; expiry is checked again before
recording redemption, after password hashing and linking.

Within one transaction it creates a new User(DRIVER,ACTIVE,phoneVerified=true),
BCrypt-hashes the chosen password, links the driver, checks the verified database
binding through DriverAccountAccessPolicy, and records redeemed_at. Rollback
removes the new account/link/verification and preserves the unused invitation.
The method returns only the internal user ID, never a session or raw token.

Phone verification represents possession of the phone-bound invitation token,
not an SMS delivery report or proof of legal identity. This relies on the token
being disclosed solely through the intended secure SMS channel, with no raw token
API/log exposure. REVIEW can activate when the actual recipient possesses the
correct token even if provider reconciliation is unresolved. Its delivery history
remains REVIEW; activation does not fabricate provider acceptance.

Existing CUSTOMER/PARTNER/DRIVER users are never promoted or relinked. A previously
consumed foundation invitation cannot silently promote its PENDING account. Those
intermediate accounts were not part of an enabled public flow. Any future recovery
or migration for real legacy PENDING accounts requires a separately verified flow.

Fifteen new tests cover final state, full login/JWT/refresh, concurrency, single use,
rollback, invalid/unknown tokens, changed phones, ineligible drivers/companies,
existing accounts and evidence requirements. Existing foundation tests keep their
behavior and check final activation is disabled in their configuration.
Expected complete suite: 668 tests, migrations unchanged at 37 changesets.
Compilation/tests remain pending local verification. Prior user-confirmed suite:
653 passed, no failures/errors/skipped, BUILD SUCCESS on 2026-10-25.
Master checklist completion statuses remain unchanged.

Before public enablement: endpoint authorization/abuse tests, safe activation-page
handling, real SMS/account contract validation, deployment secrets/TLS and release
gates remain outstanding. This internal step does not certify production readiness.
