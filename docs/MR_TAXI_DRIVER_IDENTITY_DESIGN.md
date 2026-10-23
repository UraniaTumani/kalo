# MR TAXI — Driver Identity: kontrata dhe plan migrimi

**Status:** DRAFT — projektim teknik, JO implementim  
**Data:** 2026-10-23  
**Branch i analizuar:** `feature/f42-real-gps-freshness`  
**Lidhje:** `docs/MR_TAXI_ARCHITECTURE_ADRS.md` (ADR-003), `docs/MR_TAXI_PRD.md`, `docs/MR_TAXI_MASTER_CHECKLIST.md`  
**Kufizim:** Ky dokument nuk ndryshon DB, API, kod apo statusin e ADR-003 nga PROPOSED.

## 1. Gjendja reale në kod (verifikuar)

- `UserRole` ka vetëm `CUSTOMER`, `PARTNER`, `ADMIN`; `User` ka `phone` unik, `passwordHash`, `role`, `status`, `phoneVerified`.
- `Driver` ka `company_id`, `phone` unik, `license_number` unik, `status`, `availabilityStatus`; aktualisht **nuk** ka FK `user_id`.
- `DriverServiceImpl.createDriver` merr `CreateDriverRequest` pa password dhe krijon vetëm rekordin Driver (fillimisht ACTIVE/OFFLINE), nën kompaninë e partnerit të autentikuar.
- `DriverServiceImpl.updateDriver` mund të ndryshojë telefonin; deactivation përdor `takeOutOfService` dhe refuzon deactivation kur shoferi është BUSY.
- `CustomUserDetailsService` e krijon autoritetin `ROLE_<UserRole>`; `UserStatus.PENDING` lejohet për partner onboarding, `SUSPENDED` bllokohet, `DISABLED` nuk autentikohet.
- `JwtService` vendos telefonin si `subject`, jo `driverId`. `SecurityConfig` nuk ka `/api/v1/driver/**`; `/api/v1/partner/**` kërkon PARTNER dhe `/api/v1/rides/**` CUSTOMER.
- `AuthController` ka register/customer, register/partner, login, refresh, logout dhe recovery. **Nuk ka regjistrim driver.**
- Liquibase master përfshin migrime 001–025. Migrimi i radhës duhet të jetë additive dhe i testueshëm në PostgreSQL.

## 2. Vendimi teknik i propozuar

**Rregulli i sigurisë:** Rekordi `Driver` është identitet profesional/operacional; `User` është identitet autentikimi. S'lejohet të marrësh akses në një shofer vetëm sepse dy telefona përputhen.

### Modeli minimal (faza A)

1. Shto `DRIVER` në `UserRole`, por **mos** hap endpoint-e vetëm me rol pa kontroll lidhjeje/aktivizimi.
2. Shto `drivers.user_id BIGINT NULL`, FK `drivers.user_id → users.id` dhe **UNIQUE jo-null** mbi `drivers.user_id` për lidhje 1:0..1. NULL ruan shoferët ekzistues pa login.
3. Mos plotëso `user_id` automatikisht nga `drivers.phone = users.phone`. Telefoni është atribut kontakti, jo provë pronësie/consent. Përplasje me `User` ekzistues tjetër-rol kërkon procedurë manuale/verifikim (jo ndryshim roli në heshtje).
4. Shto mapping JPA `@OneToOne(fetch = LAZY)` ose `@ManyToOne` të kufizuar me UNIQUE në `Driver` drejt `User`, sipas vendimit final për historikun e punësimit. Për MVP rekomandohet 1:1, por **nëse një shofer mund të punojë te shumë kompani ose të kalojë kompani**, projektimi i marrëdhënies duhet të ndryshojë në tabelë membership/assignment historike, jo thjesht update të `company_id`.
5. `CreateDriverRequest` dhe partner endpoints ekzistuese mbeten backward compatible: **krijimi i rekordit Driver nuk krijon login ose password automatikisht**.

### Lifecycle i llogarisë (faza B; nevojitet miratim)

- Partneri i verifikuar fton shoferin specifik të kompanisë; linku i ftesës është **one-time**, me expiry, hashed-at-rest dhe rate limit. Aktivizimi nuk kryhet vetëm me phone matching; shoferi provon kontrollin e kanalit të kontaktit dhe vendos vetë credential-in.
- Lidhja `User(DRIVER)` + `Driver.user_id` duhet të krijohet atomikisht me kontrollin e pronësisë së kompanisë; një user ekzistues me rol tjetër nuk promovohet automatikisht në DRIVER.
- Kompania mund të revokojë aksesin; deactivation dhe pezullimi të kenë sjellje të përcaktuar për udhëtim aktiv (jo revoke që lë një ride pa operator/pa protokoll).
- Phone change është proces i verifikuar: sot telefoni është JWT subject dhe login key; mos ndrysho në heshtje `Driver.phone` pa plan për `User.phone`/seancat.
- Mos dërgo password në përgjigjet e API, SMS ose logs. Mos depozito password plaintext në DB.

### Kontrata të propozuara (faza C; **nuk ekzistojnë ende**)

| Endpoint (propozim) | Actor | Qëllimi | Kufizimi server-side |
|---|---|---|---|
| `POST /api/v1/partner/drivers/{id}/invite` | PARTNER | Nis invitation | driver.companyId == partner.companyId; policy active/approved |
| `POST /api/v1/auth/driver/activate` | publik me token invite | Verify + vendos credential | one-time token; expiry/rate-limit; atomic binding |
| `GET /api/v1/driver/me` | DRIVER | Profili profesional | userId nga principal → linked driver |
| `GET /api/v1/driver/rides/current` | DRIVER | Ride i caktuar | ride.driverId == own driverId; company matching |
| `PUT /api/v1/driver/location` | DRIVER | GPS | actor↔driver, shift, company, freshness, permissions |
| `POST /api/v1/driver/rides/{id}/arriving` | DRIVER | Hap i lifecycle | assigned ride/status; idempotency |

`login` dhe `refresh` ekzistues preferohen për ripërdorim, vetëm pasi role/status verification dhe binding janë të sigurt. Endpoint-et e tjera për ride/complete do të specifikohen te ADR-005.

## 3. Strategjia e migrimit Liquibase

**Propozim `026-link-drivers-to-users.yaml` (jo i krijuar/ekzekutuar):**

1. `addColumn` `drivers.user_id BIGINT`, `nullable: true`, pa default.
2. `addForeignKeyConstraint` `fk_drivers_user` tek `users(id)`.
3. `addUniqueConstraint` `uk_drivers_user_id` (PostgreSQL lejon shumë NULL te UNIQUE).
4. Indeksi unik shërben edhe kërkimet `findByUserId`; mos shto indeks tjetër pa nevojë.
5. Shto include te master changelog vetëm bashkë me kodin JPA dhe testet e migrimit në një PR.
6. Verifiko për skema të vjetra: 25 migrimet ekzistuese, asnjë shofer historik nuk zhduket, `user_id` fillon NULL, app i vjetër vazhdon pa përplasje te schema.
7. Nuk ka backfill me `phone`; rollout invite është veprim i veçantë dhe i auditueshëm. Rollback i FK/kolonës ka kosto humbjeje link-esh: të vendoset politikë release/backup.

## 4. Rregulla të autorizimit që nuk negociohen

- `/api/v1/driver/**` kërkon `ROLE_DRIVER` në `SecurityConfig`, **plus** lidhje objekt-level, status User ACTIVE, Driver ACTIVE, company APPROVED/ACTIVE dhe ride assignment aktiv sipas operacionit.
- Për endpoint me ride id, query duhet të scope-ohet nga `driver_id` i principalit dhe kompanisë; mos beso `driverId` ose `companyId` nga request body.
- Driver nuk merr implicitisht të drejtat e PARTNER dhe nuk sheh flotën e kompanisë ose GPS e shoferëve të tjerë.
- Për driver të palidhur, inactive ose suspended: fail closed, pa leak të të dhënave. Token revocation gjatë disable/change-company të testohet, duke dalluar access token të lëshuar më parë dhe refresh token.
- Operacionet mobile retryable kërkojnë idempotency dhe consistency kontroll për statuset e ride (implementim më vonë).

## 5. Testet minimale për secilën fazë

**Migration/JPA:** migrim në PostgreSQL Testcontainers, nullable existing drivers, FK për nonexistent User, UNIQUE jo-null, re-run migration, validim Hibernate schema.

**Identity:** invite për driver të huaj 404/403, duplicate invite, expired/used token, user ekzistues CUSTOMER/PARTNER me numër të njëjtë, concurrency me dy aktivizime, password handling, deaktivizim, login/refresh/logout.

**Authorization:** anon 401, customer/partner 403 te `/driver/**`, driver i palidhur refuzohet, spoofed IDs, cross-company, suspended user, inactive driver/company, active ride mismatch, GPS update i paautorizuar dhe token revocation.

**Regression:** `./mvnw.cmd test`; `cd frontend && npm run build && npm run lint && npm run test:e2e` në stackun e izoluar, si baseline e 2026-10-23. Mos ndrysho kontejnerët lokalë prod/dev për testin.

## 6. Çështje të hapura para një migrimi real

1. A lejohet shoferi të jetë i punësuar te **më shumë se një kompani** ose të ndryshojë kompani duke ruajtur historikun e licencës/ratings? Nëse po, duhet model membership me histori në vend të një lidhjeje të thjeshtë 1:1.
2. Kush e fton shoferin: pronari i kompanisë apo edhe dispatcher i autorizuar? Aktualisht partneri është vetëm `UserRole.PARTNER`.
3. A duhet që llogaria Driver të aktivizohet vetëm pas dokumenteve të verifikuara, apo mund të hyjë për onboarding por **jo** të marrë ride?
4. Si bëhet account recovery kur shoferi ndryshon telefonin? Identiteti aktual përdor telefonin si subject JWT.
5. Çfarë ndodh kur shoferi çaktivizohet gjatë një udhëtimi aktiv? Ruaj politikën ekzistuese të refuzimit të deactivation gjatë BUSY derisa të miratohet exception workflow.

**Status:** projektimi teknik është përgatitur; **MRT-03-02 / MRT-03-03 dhe ADR-003 mbeten të hapura**. Asnjë implementim ose migrim DB nuk është kryer.
