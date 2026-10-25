# Driver authentication binding guard — pending local verification

A driver-only policy now requires User ACTIVE + phoneVerified, a linked Driver
ACTIVE, matching User/Driver phone values, and company ACTIVE/APPROVED. A database
join verifies the current binding instead of trusting only UserRole.DRIVER.
CustomUserDetailsService applies this gate; existing password login and per-request
JWT account checks therefore pick it up without new endpoints. Customer, partner
and admin status rules are unchanged, including pending partner onboarding.

RefreshTokenService applies the same driver policy before issuing/rotating.
When rotation detects an ineligible driver, DriverRefreshTokenRevocation commits
revocation of all that user's refresh tokens in REQUIRES_NEW. The later 401 and
outer transaction rollback cannot restore them. The policy check happens before
this rotation transaction modifies any refresh row, avoiding self-locking.

The current-state JWT gate rejects an existing bearer token while the binding is
invalid; it does not introduce a persistent JWT/session-version revocation epoch.
Transfer, global logout semantics and permanent access-token invalidation after
eligibility is restored remain separate gates. Object-level authorization on
future driver ride/location endpoints is still required.

No account is promoted to ACTIVE and no phone verification flag is set here.
The redemption foundation still creates PENDING/unverified accounts and remains
disabled by default. Public activation, actual delivery and report scheduling
remain disabled. This patch does not complete Driver Identity or certify production.

Seventeen new tests cover policy/query delegation, active/unlinked/pending/
unverified drivers, all authentication paths after phone/status/company/link
changes, role separation, and refresh revocation surviving a failed HTTP request.
The latter creates multiple sessions and confirms old refresh tokens remain
revoked after driver eligibility is restored. Existing four details-service tests
are adapted to the new policy dependency.

Expected full suite: 653 tests; no migration, 37 changesets remain. Local execution
is pending because dependencies are unavailable in the implementation environment.
Prior user-confirmed suite: 636 passed, 0 failures/errors/skipped, BUILD SUCCESS
on 2026-10-25. Master checklist statuses are unchanged; no Git commit/push performed.
