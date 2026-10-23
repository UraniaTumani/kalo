# MR TAXI — Architecture Decision Records (ADR)

**Status i dokumentit:** i përzier — ADR-001 i pranuar në nivel drejtimi, ADR-002 me drejtim produkti të pranuar dhe framework në provë; ADR-003..006 mbeten PROPOSED  
**Data:** 2026-10-23  
**Branch bazë:** `feature/f42-real-gps-freshness`  
**Lidhje:** `docs/MR_TAXI_PRD.md` (draft), `docs/MR_TAXI_MASTER_CHECKLIST.md` (MRT-00-07), `docs/MR_TAXI_TECHNICAL_INVENTORY.md`.  
**Rregulli:** çdo ADR përmban kontekst, propozim, alternativa, pasoja, kërkesa dhe kushte pranimi. Asnjë migrim, endpoint ose aplikacion mobile nuk autorizohet vetëm nga ky draft.

---

## ADR-001 — Mbajmë Spring Boot modular monolith si backend-in e vetëm

**Status:** ACCEPTED — drejtimi arkitekturor i miratuar në bisedë, 2026-10-23; implementimet konkrete vazhdojnë të kërkojnë review/testim  
**Referenca:** PRD BR-01, BR-02; MRT-00-07, MRT-07-01

### Konteksti

Kodi ekzistues ka modulet `auth`, `user`, `partner`, `driver`, `vehicle`, `assignment`, `ride`, `location`, `document`, `notification`, `rating`, `admin`, `support`. Ka PostgreSQL me Liquibase, JWT/refresh, state machine për ride dhe constraints DB. Bazë e verifikuar në 2026-10-23: 443 teste backend, 124 E2E Chrome, build frontend i suksesshëm (referojuni evidencës MRT-00-05).

### Propozimi

- Një backend Spring Boot mbetet burimi i vetëm i rregullave, transaksioneve, identiteteve dhe rezervimeve.
- Klientë të veçantë: **Customer App**, **Driver App**, **Partner Web**, **Admin Web**.
- Kontrata `/api/v1` mbetet backward compatible; shtesat mobile krijohen si endpoint-e additive. Nuk kopjohet `RideService` në aplikacionet mobile.
- Ruajmë transaksionet dhe mbrojtjet PostgreSQL: `uk_active_ride_per_customer`, `uk_active_ride_per_driver`, indekset e assignment dhe unique `ride_request_id, company_id`.
- Boundary të qarta brenda monolitit (auth, ride, location, finance) dhe shërbime të testueshme pa dependency ciklike.

### Alternativat

1. **Microservices menjëherë:** izolim teorik i scale, por shton transaksione të shpërndara, retries dhe operim shumë më kompleks.
2. **Backend i ri për mobile:** mund të nisë shpejt, por dyfishon rregullat e autorizimit, ride state dhe finance.
3. **Monoliti ekzistues (i propozuar):** më pak risk dhe ripërdorim i testuar.

### Pasoja dhe prova

- Avantazh: një vend për integritetin e ride dhe komisioneve.
- Kufizim: release dhe scale kryesisht i përbashkët deri te matje reale që justifikojnë ndarje.
- Pranimi: diagrami i moduleve, dependency rules dhe kontrata të reja testohen pa thyer 443/124 baseline. Rollback për ndryshimet DB duhet planifikuar individualisht.

---

## ADR-002 — Dy aplikacione native mobile, React Native si kandidat për feasibility

**Status:** ACCEPTED për ndarjen Customer App / Driver App; React Native mbetet **PROPOSED, i kushtëzuar nga PoC iOS/Android**, jo i miratuar teknikisht si implementim final (dakordësim drejtimi, 2026-10-23)  
**Referenca:** PRD DEC-07, CUS/DRV; MRT-05-01, MRT-06-01, MRT-12-04

### Konteksti

Web SPA aktuale është React 19/TypeScript dhe mbulon Partner/Admin dhe customer demo. Safari në iPhone transmetoi GPS real në testin e mëparshëm, por browser-i nuk garanton background location.

### Propozimi

- Dy produkte me akses/rrjedha të ndara: Customer App dhe Driver App iOS/Android. Partner/Admin mbeten web.
- **React Native** (me rrugë native-module kur nevojitet) është hipoteza preferenciale për kod të përbashkët UI dhe TypeScript; të verifikohet me PoC në pajisje fizike.
- Customer: auth, kërkim/zgjedhje kompanie, ride status, GPS freshness, histori, support.
- Driver: identitet personal, turn online/offline, assigned ride, status transitions, GPS, mbyllje udhëtimi me taksimetër.
- API client të ndara nga UI; DTO të versioneve dhe error contract të dokumentuar; mos importoni kode të frontend web që varen nga browser APIs.

### Alternativat

- **Swift/Kotlin native:** kontroll maksimal mbi lifecycle/background; dy baza kodi dhe kosto më e lartë.
- **Flutter:** UI cross-platform, por kërkon stack Dart dhe integrime native.
- **PWA/browser vetëm:** nuk garanton funksionimin e kërkuar të GPS në background.

### Kushte pranimi të PoC

- Android dhe iOS në **pajisje fizike**, foreground + background + app terminated/OS restart sipas kufizimeve.
- Permissions revoke/deny, battery saver, offline/reconnect, high/low accuracy, push wake-up, session logout.
- Matje të konsumit të baterisë, freshness dhe intervalit të raportimit. Nëse React Native nuk i plotëson, vlerësohet native i dedikuar për Driver App.
- **Nuk premtohet tracking i pandërprerë**: OS mund ta pezullojë ose ndalojë aplikacionin.

---

## ADR-003 — Identiteti individual i shoferit dhe autorizimi sipas objektit

**Status:** PROPOSED  
**Referenca:** PRD BR-02, SEC-01..SEC-03; MRT-03-02, MRT-03-03, MRT-09-02

### Konteksti

`Driver` është sot rekord i flotës me `company_id`, pa lidhje me `User`. `SecurityConfig` lejon `/api/v1/partner/**` vetëm për PARTNER, dhe ride state modifikohet nga Partner; `/api/v1/rides/**` është CUSTOMER.

### Propozimi

1. Projektim additive i lidhjes `User ↔ Driver` (marrëdhënie dhe rregulla unike të miratohen në migration design). Shoferi merr `DRIVER` role dhe credentials të veçanta; kompani kontrollon onboarding/aktivizim.
2. API të reja `/api/v1/driver/me`, `/api/v1/driver/shift`, `/api/v1/driver/location`, `/api/v1/driver/rides/current` dhe operacione të autorizuara ride — **emrat janë propozime, jo endpoint-e ekzistuese**.
3. Identity merret nga JWT/server, **jo** nga `driverId` i dërguar nga klienti. Për ride: verifikohet `ride.driver_id == authenticatedDriver.id`, kompania, statusi, assignment dhe kufizimet e licencës.
4. Partneri ruan aftësinë ekzistuese të caktimit të shoferit dhe override të audituar vetëm sipas politikës së miratuar; nuk i jepet rol PARTNER shoferit.
5. Revoke sessions kur shoferi çaktivizohet ose ndryshon kompani; audit login/device, authorization deny, transition actor.

### Alternativat

- **Shoferi hyn me token të kompanisë:** refuzohet (identitet dhe accountability i dobët).
- **User/Driver të shkëputur me PIN statik:** refuzohet për kontrollin e aksesit dhe revokimin.
- **User/Driver linkage e dedikuar:** e propozuar, me migration dhe security tests.

### Teste të domosdoshme

Cross-company access, spoofed driver ID, unauthorized ride transition, blocked/deactivated driver, revoked tokens, concurrent assignments, password reset, logout; verifiko 401/403/404 pa leak të ekzistencës.

---

## ADR-004 — GPS native, sessions, freshness dhe privatësi

**Status:** PROPOSED  
**Referenca:** PRD BR-04, LOC-01..LOC-05; MRT-06-03, MRT-07-07, MRT-09-06

### Konteksti

`DriverLocation` ruan koordinatat dhe `locationUpdatedAt` e pozicionit aktual për shofer. Endpoint-i ekzistues `PUT /api/v1/partner/drivers/{driverId}/location` kërkon JWT partner dhe `latitude/longitude`. Testi iPhone Safari nuk është garanci GPS background.

### Propozimi

- Driver App transmeton vendndodhje vetëm për sesion pune të autorizuar, me permissions transparente dhe indikim të gjendjes GPS; location ingestion kontrollon driver actor, shift/ride scope dhe statusin.
- Propozim payload i ri: `latitude`, `longitude`, `deviceTimestamp`, `accuracyMeters`, `sequenceId`/idempotency, `shiftId` (detajet e kontratës miratohen pas PoC).
- Serveri regjistron `receivedAt`; nuk beson verbërisht te ora e pajisjes. Reject/ignore out-of-order, implausible accuracy/movement dhe duplicates sipas politikës së përcaktuar.
- **ONLINE ≠ GPS fresh**. Freskia llogaritet server-side me threshold të konfigurueshëm dhe shfaqet në Customer App; GPS stale nuk etiketohet si live dhe nuk përdoret për premtime ETA.
- Kufizo GPS të ndarë te klienti me ride aktiv dhe driver assignment aktual. Minimizim, retention dhe audit access të përcaktuara para pilotit.
- Pozicioni aktual mund të mbetet në `driver_locations`; historiku i nevojshëm për operim/incident nuk shtohet pa qëllim dhe afat mbajtjeje.

### Alternativat

- Vetëm browser GPS (i papërshtatshëm për background);
- interval strikt p.sh. 5 sekonda gjithmonë (battery/network cost);
- event-based/adaptive native GPS (kandidat për matje në PoC).

### Pranimi

Permission denied, stale, offline/reconnect, out-of-order, impossible coordinate, cross-tenant, ride cancelled, assignment changed, GPS disabled, iOS/Android OS limitations; matje dhe kërkesa privacy të dokumentuara.

---

## ADR-005 — Ride authority, status machine dhe retry

**Status:** PROPOSED  
**Referenca:** PRD DEC-01, DEC-04, BR-03; MRT-07-01..MRT-07-06

### Propozimi

- Ruajmë state machine ekzistuese `REQUESTED → DRIVER_ASSIGNED → DRIVER_ARRIVING → DRIVER_ARRIVED → IN_PROGRESS → COMPLETED`; dhe `DECLINED`, `NO_RESPONSE`, `CANCELLED`.
- Kompania pranon kërkesën dhe cakton shofer/automjet. Shoferi propozohet si aktori normal i fazave operative (`ARRIVING`, `ARRIVED`, `START`, `COMPLETE`), me kontroll të ride/assignment nga serveri.
- Partner override (nëse miratohet) kërkon reason, actor identity dhe audit. **Nuk heqim endpoint-et ekzistuese para migrimit të klientëve.**
- Driver acceptance (PRD DEC-04) mbetet vendim i hapur; mos shto status/transitions të rinj pa ADR specifik dhe teste.
- Idempotency për komandat mobile dhe retries, me concurrency protection nga transaksionet ekzistuese/indekset DB.

### Alternativat

- Të gjitha tranzicionet vetëm nga Partner Web (nuk i jep shoferit control në terren).
- Shoferi merr të gjitha kompetencat e kompanisë (cenon autorizimin dhe dispatch).
- Ndarje dispatch / ride execution me override të kontrolluar (propozimi).

### Pranimi

Matrix `actor × ride status × action`, race conditions, retries/network loss, audit, 401/403, mismatch company/driver, testet ekzistuese vazhdojnë të kalojnë.

---

## ADR-006 — Taksimetri, pagesa direkte dhe ledger B2B i komisionit

**Status:** PROPOSED  
**Referenca:** PRD BR-05..BR-08, PAY-01..PAY-06, DEC-02/03/08; MRT-08-01..MRT-08-10

### Konteksti

`Ride.finalAmount` ekziston dhe `CompleteRideRequest` pranon një `BigDecimal finalAmount` >0 me max dy shifra pas presjes. **Nuk ekziston në këtë kontratë** `paymentMethod`, `paymentStatus` ose `commissionEntry`.

### Propozimi

1. **Çmimi final buron nga taksimetri**, jo Haversine/GPS. Regjistrojmë shumën/valutën e taksimetrit dhe operatorin që e konfirmon.
2. Klienti paguan **cash ose POS fizik** direkt te shoferi/kompania. Pa checkout online, wallet apo MR TAXI si payment processor në MVP.
3. **Ride status, Payment status, Commission status** janë të ndara. `Ride.COMPLETED` nuk provon `Payment.COLLECTED`. Pagesa cash/POS e deklaruar nga shoferi duhet të dallohet nga rakordimi i konfirmuar.
4. Shto model ledger **append-only/auditueshëm** me `rideId`, `companyId`, rate-plan version, currency, calculatedAmount, eligibilityReason, correction/refund entries, createdAt, unique business key për të shmangur duplicate commission nga retries.
5. Komisioni është detyrim **B2B i kompanisë ndaj MR TAXI**, jo pagesë e klientit në platformë. Për MVP, faturimi/rakordimi periodik (p.sh. mujor) me company statement.
6. **100 lekë për ride** mbetet vetëm **hipotezë** për pilotin, jo tarifë aktive e miratuar. Rate-plan duhet versionuar dhe të lejojë ndryshime të ardhshme.
7. Për MVP, rides të anuluara/declined/no-response nuk krijojnë komision (sipas rekomandimit të PRD DEC-08, për miratim formal); për completed përcaktohen exclusions/disputes dhe correctives.
8. Nuk modifikojmë `CompleteRideRequest` deri sa të vendoset se kush e deklaron pagesën dhe çfarë ndodh kur taksimetri dihet por pagesa nuk është arkëtuar.

### Alternativat

- Tarifa fikse e paguar nga klienti në aplikacion (ndryshon produktin dhe kërkon payment/compliance).
- Komision direkt si kolonë e Ride pa ledger (marrëveshje dhe correction të paaudituara).
- Ledger dhe invoicing B2B i veçantë (propozimi).

### Pranimi

Komision 1 herë për ride eligible, zero për ineligible, retry idempotent, charge correction/credit note, cancellation rule, reconciliation, tenant isolation, VAT/invoicing policy lokale e verifikuar nga këshilltar kompetent.

---

## Vendime që kërkojnë miratim para implementimit

| PRD | Pyetja | Propozimi i ADR | Status |
|---|---|---|---|
| DEC-01 | Kush mbyll ride? | Shoferi normalisht; override i audituar nga kompania | OPEN |
| DEC-02 | Si vërtetohet pagesa cash? | Vetëdeklarim i dalluar nga arkëtim i rakorduar | OPEN |
| DEC-03 | Komisioni | Flat në pilot, tarifa 100 ALL vetëm hipotezë | OPEN |
| DEC-04 | A konfirmon shoferi caktimin? | Po, por status/timeout kërkojnë vendim të veçantë | OPEN |
| DEC-05 | Ride PIN | Nuk vendoset pa UX/security review | OPEN |
| DEC-06 | Dokumentet e detyrueshme | Politikë sipas juridiksionit | OPEN |
| DEC-07 | Framework | React Native PoC në pajisje fizike | OPEN |
| DEC-08 | Anulimet | Pa komision kur shërbimi s'është përfunduar në MVP | OPEN |

## Hapat e ardhshëm

- Review PRD + ADR nga pronari; çdo ADR kalon nga **PROPOSED** në **ACCEPTED** ose **REJECTED** me datë, arsye dhe reviewer.
- Pastaj planifikojmë migrimet/DTO/API të Driver Identity, PoC native location, matrix të ride transitions dhe finance data model.
- Për secilin ndryshim: acceptance tests, impact analysis, backward compatibility, rollbacks, QA/security review. Mos ndryshoni `main` automatikisht.

**MRT-00-07 qëndron `[ ]`: ADR-001 është pranuar si drejtim; ADR-002 ka miratim të ndarjes së produkteve, por React Native pret PoC; ADR-003..006 dhe vendimet DEC-01..08 mbeten për review.**
