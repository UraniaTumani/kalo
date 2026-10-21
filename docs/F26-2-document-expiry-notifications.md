# F26.2 – Stored document-expiry alerts (safe DEV rollout)

This change is deliberately opt-in and is **not active after a normal merge**.

## Safety
- No rows are deleted or updated by migration 025. It only adds a nullable
  `company_notifications.notification_key` column and a unique index.
- Old ride notifications continue using `NULL` for this new column.
- Expiry alerts are created in the existing company-scoped inbox.
- Daily sweep is disabled unless `app.notifications.document-expiry.enabled=true`.
- All alert rows are guarded by a PostgreSQL unique index and `ON CONFLICT DO NOTHING`.
- Expiry calculations use the Europe/Tirane calendar date. A document is expired
  only after its expiry date.
- A changed expiry date produces a new historical alert; old alerts are **not**
  silently removed. The Dashboard reflects the current date.

## DEV verification procedure
1. Review migration 025 and **back up DEV data** before starting backend on this branch:
   Liquibase runs on Spring Boot startup even when the scheduler is disabled.
2. Run `./mvnw -Dtest=DocumentExpiryNotificationSweepIntegrationTest test`
   with a disposable PostgreSQL Testcontainers setup. Never point these tests at
   a shared or production database.
3. Run the normal backend unit/integration tests and frontend `npm run build`.
4. Only after the above tests succeed, explicitly enable
   `app.notifications.document-expiry.enabled=true` in an isolated DEV run.
5. Check a test company's notifications at >30, <=30, <=7 and expired, and
   confirm repeated sweeps create no duplicates.
6. Keep the property **disabled in production** until separately approved.

## What F26.2 does not do
- No email, SMS, push, or browser popups.
- No edits to existing document expiry dates or ride data.
- No automatic document renewals.
- No new partner API endpoints.
