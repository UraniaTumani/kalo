# Invitation scheduler — pending local verification

The scheduler is absent unless app.driver-invitation.scheduler-enabled=true.
Defaults remain disabled; no credentials, key bean or transport bean are added.
When explicitly enabled it requires worker-enabled=true, a nonblank key ID, an
encryption cipher bean and a transport bean. Invalid prerequisites fail startup.
This does not certify the activation page, provider contract or production readiness.

The dedicated PostgreSQL advisory lock is 4612004. A sweep selects at most ten
entries by default (configurable batch-size, range 1..50). Its initial delay is
one minute and polling delay thirty seconds. Both use configurable Duration
strings. It selects due entries for the configured key and invalid invitations
that require ciphertext cleanup. Valid entries using unknown key versions remain
held; key rotation and old-key resolution are not implemented.

Each proxied worker process() now runs REQUIRES_NEW, independently of the advisory
lock's outer transaction. Earlier accepted entries remain committed if a later
entry fails. Unexpected failures stop the batch and are logged without exception
contents. Pending entries are reconsidered on a future sweep. Bounded provider
HTTP timeouts remain essential because row locks span the send call.

Fourteen new tests cover conditional registration, prerequisites, advisory-lock
delegation, bounded selection, key/time filtering, and actual per-entry commit
isolation with a fake provider. No live provider is contacted.
Expected full test count: 566, subject to successful local execution.
No checklist completion status, deployment, public issuance or activation is changed.
