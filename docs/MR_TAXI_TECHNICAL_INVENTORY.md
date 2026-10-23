# MR TAXI — INVENTARI TEKNIK I KODIT EKZISTUES

**Versioni:** 0.1 / auditim paraprak  
**Data:** 2026-10-23  
**Repository:** `UraniaTumani/kalo`  
**Branch:** `feature/f42-real-gps-freshness`  
**Commit i analizuar:** `b35cda8fa878ccdc07867c58f43cfe963f8a8d83`  
**Checklist:** `MRT-00-04` — **HAPUR** derisa të verifikohen të gjitha DTO/API dhe kontratat e sigurisë.

## 1. Përmbledhja objektive e repository-t

Lexim vetëm-read-only i pemës rekursive Git në commit-in e shënuar.

| Kategoria | Numri | Shënim |
|---|---:|---|
| Skedarë gjithsej në tree | 479 | Vetëm blobs, pa directories |
| Skedarë Java backend (`src/main/java`) | 237 | Konfirmuar nga tree |
| Controller-a Java | 24 | Skedarë me suffix `Controller.java` |
| Klasa nën paketat `entity/` | 17 | Përfshirë BaseEntity |
| Skedarë në `src/test/` | 50 | Ekzistenca ≠ teste të kaluara |
| Migrime `changes/001–025` | 25 | Liquibase |
| Skedarë frontend `src/features/` | 37 | Përfshin faqe dhe ndihmës |
| Ekrane frontend `*Page.tsx` | 25 | Në shtatë fusha funksionale |

**Kujdes:** ky dokument është inventar statik, jo rezultat build/testesh. README kryesor dhe `frontend/README.md` kanë kufizime MVP të vjetruara krahasuar me skedarët aktualë.

## 2. Modulet backend dhe controller-at

| Fusha | Controller / skedarë kryesorë | Gjendja |
|---|---|---|
| auth | `auth/controller/AuthController.java`, `AdminPasswordResetController.java` | Ekzistojnë |
| user | `user/controller/UserController.java` | Ekziston |
| partner | `partner/controller/PartnerController.java`, `PartnerAvailabilitySettingsController.java`, `PartnerOperationalSettingsController.java` | Ekzistojnë |
| driver | `driver/controller/PartnerDriverController.java` | Ekziston |
| vehicle | `vehicle/controller/PartnerVehicleController.java` | Ekziston |
| assignment | `assignment/controller/PartnerDriverVehicleAssignmentController.java` | Ekziston |
| location | `location/controller/PartnerDriverLocationController.java` | Ekziston; browser tracking është test |
| ride | `ride/controller/PartnerRideController.java`, `RideSearchController.java`, `PublicAvailabilityController.java` | Ekzistojnë |
| rating | `rating/controller/RideRatingController.java` | Ekziston |
| document | `document/controller/PartnerDocumentController.java`, `PartnerDriverDocumentController.java`, `PartnerVehicleDocumentController.java` | Ekzistojnë; F12 |
| notification | `notification/controller/PartnerNotificationController.java` | Ekziston; nuk nënkupton push native |
| geocoding | `geocoding/controller/GeocodingController.java` | Ekziston |
| support | `support/controller/SupportController.java`, `AdminSupportController.java` | Ekzistojnë |
| admin | `admin/controller/AdminPartnerController.java`, `AdminRideController.java`, `AdminUserController.java` | Ekzistojnë |

Controller-at janë numëruar sipas skedarëve. Rrugët e API-ve duhet të verifikohen individualisht për inventarin final.

## 3. Endpoint-e të verifikuara nga anotimet Java

| Bazë | Rrugët / veprimet e identifikuara |
|---|---|
| `/api/v1/auth` | `/register/customer`, `/register/partner`, `/login`, `/refresh`, `/logout`, `/password/forgot`, `/password/reset` |
| `/api/v1/rides` | `/search`, `/current`, `/{rideId}`, `/{rideId}/driver-location`, `/history`, `/{rideId}/cancel` dhe një POST select që kërkon verifikim të rrugës së plotë |
| `/api/v1/partner/rides` | listim, `/{rideId}/accept`, `/decline`, `/driver-arriving`, `/driver-arrived`, `/start`, `/complete` |
| `/api/v1/partner/drivers` | krijim, listim, get-by-id, update, deactivate/delete mapping, `/{driverId}/availability` |
| `/api/v1/partner/drivers/{driverId}/documents` | `/upload`, listim dhe `/{documentId}/file` |
| `/api/v1/partner/vehicles` | krijim, listim, get-by-id, update, delete mapping |
| `/api/v1/partner` | `/me`, `/readiness`, `/submit-verification` |

**Jo ende të përfunduara:** inventari i plotë i të gjitha HTTP methods/routes, DTO-ve, roleve dhe validations. Kjo listë është provë fillestare, jo API catalog përfundimtar.

## 4. Entitetet e identifikuara

`BaseEntity`, `User`, `RefreshToken`, `PasswordResetRequest`, `TaxiCompany`, `CompanyOperatingHours`, `Driver`, `Vehicle`, `DriverVehicleAssignment`, `DriverLocation`, `Document`, `RideRequest`, `RideOffer`, `Ride`, `RideRating`, `CompanyNotification`, `SupportRequest`.

### Fakte të rëndësishme

- Backend-i ka një model ekzistues për kompaninë, shoferin, automjetin dhe lidhjet mes tyre. **Nuk duhen ri-krijuar.**
- Ekzistojnë `RideRequest`, `RideOffer` dhe `Ride` veçmas. **Customer App dhe Driver App duhet të përdorin të njëjtin sistem.**
- Ekziston `DriverLocation` dhe testi i mëparshëm konfirmoi dërgimin e pozicioneve reale nga iPhone Safari në PostgreSQL.
- Në ndërfaqen e verifikuar `DriverResponse` nuk ka identitet login të shoferit; ky është kandidat për zgjerim të kontrolluar, jo ripunim automatik.
- Nuk u identifikua në listën e entiteteve një `CommissionLedger` ose `Invoice`; kjo nuk është provë absolute e mungesës së logjikës financiare diku tjetër, prandaj kërkon auditim.

## 5. Liquibase — migrimet ekzistuese

| Intervali | Qëllimi i konfirmuar nga emrat e skedarëve |
|---|---|
| 001–006 | Users, TaxiCompany, Documents, Drivers, Vehicles, DriverVehicleAssignment |
| 007–008 | Driver availability dhe locations |
| 009–014 | Ride requests, offers, rides, execution fields, retries dhe ratings |
| 015–016 | Konfigurime operative, zona shërbimi dhe orare |
| 017–019 | Invariante konkurrence, gjendje kompanie, normalizim telefonash |
| 020–025 | Refresh tokens, support, notifications, password reset, OTP dhe notification deduplication |

**Kërkon verifikim të dytë:** kolonat, indekset, constraints, foreign keys dhe pajtueshmëria me kodin Java. Nga emri i migrimit nuk mund të përfundohet që çdo rregull zbatohet saktë.

## 6. Ekranet e frontend-it që ekzistojnë

### Public / Auth
`GuestLandingPage`, `LoginPage`, `RegisterPage`, `ForgotPasswordPage`, `ResetPasswordPage`, `ProfilePage`.

### Customer
`BookRidePage`, `CurrentRidePage`, `RideHistoryPage`.

### Partner
`PartnerDashboardPage`, `PartnerDriversPage`, `PartnerVehiclesPage`, `PartnerAssignmentsPage`, `PartnerRidesPage`, `PartnerDocumentsPage`, `PartnerNotificationsPage`, `PartnerAvailabilityPage`, `PartnerSettingsPage`.

### Admin
`AdminVerificationPage`, `AdminCompaniesPage`, `AdminUsersPage`, `AdminRidesPage`, `AdminPasswordResetsPage`, `AdminSupportPage`.

### Support
`SupportPage`.

**Dallimi i rëndësishëm:** këto janë faqe **React web**, jo Customer/Driver native iOS/Android.

## 7. Dokumentet dhe GPS — pjesë që duhet të ripërdorim

**Dokumentet:** `DocumentType` ka 9 kategori të dalluara: Company (2), Driver (2), Vehicle (5). `FleetDocumentsCard.tsx` ka upload dhe private download. Duhet të auditohet authorization i çdo skenari dhe mbështetja reale në production storage, jo të ndërtohet F12 nga zero.

**GPS browser test:** `frontend/src/features/partner/useDriverTracking.ts` ka interval upload `25_000 ms` dhe gjendje `trackedDriverIds` në memory. `PartnerDriversPage.tsx` përmban veprimin `Rifillo GPS`. Ky kod ndihmon për testim, por nuk zëvendëson GPS-in native të Driver App.

## 8. Testet dhe automatizimi ekzistues

- 50 skedarë nën `src/test/`.
- Teste Playwright nën `frontend/e2e/`, p.sh. `auth.spec.ts`, `customer.spec.ts`, `partner.spec.ts`, `current-ride-state.spec.ts`, `driver-online.spec.ts`, `vehicle-management.spec.ts`.
- `frontend/tests/fleet-documents.spec.ts` dhe `frontend/tests/gpsFreshness.test.mjs`.
- `frontend/package.json` përcakton `build`, `lint`, `test:e2e`, `test:regression`, `test:documents`.
- NUK janë ekzekutuar testet e këtij commit-i në këtë auditim. Rezultatet historike nuk përbëjnë garanci për degën aktuale.

## 9. Gap analysis — jo detyra për të nisur pa plan

| Blloku | Ripërdorim | Mbetet për projektim |
|---|---|---|
| Driver App | Driver, Vehicle, Assignment, Ride, Location API | Login personal, native GPS, driver authorization, notifications |
| Customer App | Auth, search, offer, ride, rating | Native UX, push, location freshness, live data |
| Partner | Të gjitha ekranet dhe API-të ekzistuese | Dispatch më efikas, readiness dhe finance UX |
| Documents | F12, enum, verification flow | Jurisdiction policy, ekspiry enforcement, private production storage |
| Finance | Final fare në ride sipas README | Payment state, commission ledger, invoice/reconciliation |
| Quality | Test suites dhe CI file | Ekzekutim i auditueshëm dhe katalog i mbulimit |

## 10. Çfarë duhet për mbylljen e MRT-00-04?

- [x] Pemë Git e lexuar dhe numërim i moduleve / entiteteve / controller-ave / migrimeve / ekraneve / testeve.
- [x] Rrugë bazë API të verifikuara nga controller-a për Auth, Ride, Driver, Vehicle, Documents, Partner.
- [ ] Të gjitha endpoint-et me metodë HTTP, input DTO, output DTO, authorization dhe shembuj gabimesh.
- [ ] Entitetet: fushat kryesore, relacionet, foreign keys, constraints dhe indeksat.
- [ ] Auditimi i të gjitha migrimeve dhe implementimeve të sigurisë.
- [ ] Verifikimi i route guards/roles dhe listës së përfunduar të feature-eve frontend.
- [ ] Miratimi i pronarit për dokumentin final.

**Status:** `MRT-00-04` mbetet `[ ]`. Ky dokument është provë për punën e nisur, jo deklarim përfundimi.
