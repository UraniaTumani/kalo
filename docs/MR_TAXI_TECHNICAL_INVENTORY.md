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


## 11. Auditimi i API-ve dhe kontrolli i roleve — faza 2 (2026-10-23)

**Burimi:** skedarët Java realë në branch-in `feature/f42-real-gps-freshness`. Konstatimet janë statike, **jo** test ekzekutimi i endpoint-eve.

### 11.1 Matrica aktuale e aksesit HTTP nga `SecurityConfig.java`

| Prefix | Kontrolli HTTP | Kuptimi praktik |
|---|---|---|
| `/api/v1/auth/**` | `permitAll` | Login, regjistrim, refresh etj.; kontrollet e brendshme të shërbimit duhen shqyrtuar veç |
| `/api/v1/public/**` | `permitAll` | Endpoint-e publike, dokumentohen si read-only |
| `/api/v1/partner/**` | `hasRole("PARTNER")` | Partneri menaxhon flotën dhe gjendjen e udhëtimeve |
| `/api/v1/admin/**` | `hasRole("ADMIN")` | Administrimi |
| `/api/v1/rides/**` | `hasRole("CUSTOMER")` | Rezervimet, historiku dhe vendndodhja e shoferit për klientin |
| `/actuator/**` | `hasRole("ADMIN")`, përjashto `/health` | Health publik, metrikat të kufizuara |
| Rrugët e tjera | `authenticated()` | Kërkojnë autentikim, por duhen audituar në nivel shërbimi/objekti |

**Gjetje e verifikuar:** në `SecurityConfig.java` **nuk ka ende** `/api/v1/driver/**` dhe `hasRole("DRIVER")`. Driver App duhet të marrë API dhe identitetin e vet; **nuk duhet** të përdorë JWT të kompanisë. Kjo mbështet `MRT-03-02` dhe `MRT-03-03`.

**Kujdes:** kontrolli i prefix-it nuk provon vetvetiu autorizimin e nivelit të objektit. Duhet të verifikohet që përdoruesi lexon/ndryshon vetëm ride, kompani, shoferë dhe dokumente për të cilat ka të drejtë.

### 11.2 Endpoint-e kryesore të konfirmuara

| HTTP | Endpoint | Roli aktual sipas SecurityConfig | DTO / rezultat |
|---|---|---|---|
| POST | `/api/v1/auth/login` | Publik | Login DTO / token |
| POST | `/api/v1/auth/register/customer` | Publik | Regjistrim klienti |
| POST | `/api/v1/auth/register/partner` | Publik | Regjistrim kompanie |
| POST | `/api/v1/auth/refresh` | Publik në nivel HTTP | Refresh i token-it; logjika e shërbimit duhet kontrolluar |
| POST | `/api/v1/rides/search` | CUSTOMER | `CreateRideRequest` → `RideSearchResponse` |
| POST | `/api/v1/rides/requests/{rideRequestId}/select` | CUSTOMER | `SelectTaxiOfferRequest` → `RideResponse` |
| GET | `/api/v1/rides/current` | CUSTOMER | `RideResponse` |
| GET | `/api/v1/rides/{rideId}/driver-location` | CUSTOMER | `DriverLocationResponse` |
| GET | `/api/v1/rides/history` | CUSTOMER | `Page<RideResponse>` |
| GET | `/api/v1/rides/{rideId}` | CUSTOMER | `RideResponse` |
| POST | `/api/v1/rides/{rideId}/cancel` | CUSTOMER | `RideResponse` |
| GET | `/api/v1/partner/rides` | PARTNER | `Page<PartnerRideResponse>`, filter `status` / `active` |
| POST | `/api/v1/partner/rides/{rideId}/accept` | PARTNER | `AcceptRideRequest` → `RideResponse` |
| POST | `/api/v1/partner/rides/{rideId}/decline` | PARTNER | `RideResponse` |
| POST | `/api/v1/partner/rides/{rideId}/driver-arriving` | PARTNER | `RideResponse` |
| POST | `/api/v1/partner/rides/{rideId}/driver-arrived` | PARTNER | `RideResponse` |
| POST | `/api/v1/partner/rides/{rideId}/start` | PARTNER | `RideResponse` |
| POST | `/api/v1/partner/rides/{rideId}/complete` | PARTNER | `CompleteRideRequest` → `RideResponse` |
| PUT | `/api/v1/partner/drivers/{driverId}/location` | PARTNER | `UpdateDriverLocationRequest` → `DriverLocationResponse` |
| GET | `/api/v1/partner/drivers/{driverId}/location` | PARTNER | `DriverLocationResponse` |
| POST | `/api/v1/partner/drivers/{driverId}/documents/upload` | PARTNER | Multipart upload |
| GET | `/api/v1/partner/drivers/{driverId}/documents/{documentId}/file` | PARTNER | Download dokumenti |

Ky është **subset i verifikuar**, jo lista e plotë e 24 controller-ave.

### 11.3 DTO e pagesës në `CompleteRideRequest.java`

Endpoint-i ekzistues i përfundimit të udhëtimit kërkon **vetëm** `finalAmount: BigDecimal`:
- `@NotNull` — shuma kërkohet;
- `@DecimalMin(value="0.0", inclusive=false)` — mbi zero;
- `@Digits(integer=10, fraction=2)` — maksimumi dy shifra pas presjes.

**Nuk ka** `paymentMethod` ose `paymentStatus` në këtë DTO. Futja e shumës së taksimetrit ekziston, por deklarimi/konfirmimi i pagesës është **punë e ardhshme**, jo veçori tashmë e përfunduar. Kjo lidhet me `MRT-06-09` dhe `MRT-08-*`.

### 11.4 DTO e GPS në `UpdateDriverLocationRequest.java`

Endpoint-i merr **vetëm** `latitude` dhe `longitude`, me kufij `[-90,90]` dhe `[-180,180]`. Nuk merr ende `deviceTimestamp`, `accuracy`, sequence/dedupe ID apo source. Kjo shpjegon pse zgjerimi i kontratës native GPS duhet projektuar dhe testuar, jo thjesht të përdorë pa ndryshim payload-in e browser-it.

### 11.5 Kontrolli i pronësisë në Location Service

`DriverLocationServiceImpl`:
- identifikon kompaninë nga përdoruesi i autentikuar;
- kontrollon që kompania të jetë `APPROVED` dhe `ACTIVE`;
- kërkon shoferin me `findByIdAndCompanyId(driverId, companyId)`;
- kontrollon statusin `ACTIVE` të shoferit;
- për lexim e kufizon sipas `driverId + driverCompanyId`.

Kjo është evidencë e **kontrollit partner/tenant në këtë shërbim**, jo provë e sigurisë së çdo endpoint-i. Versioni për Driver App duhet të lidhë JWT individual me identitetin e shoferit dhe ta kufizojë te profili i vet.

### 11.6 Vendimi i auditimit

- **Ruajmë** `RideService`/state machine, ofertat, `DriverLocationService` dhe API-të ekzistuese për Partner dhe Customer.
- **Nuk rihapim** strukturën bazë të databazës pa verifikuar lidhjet e tanishme.
- **Projektojmë** API-të specifike për Driver mbi të njëjtat shërbime, me autorizim objekti.
- **Projektojmë** recording të pagesës ndaras nga statusi `COMPLETED`, komisionet dhe audit.
- **MRT-00-04 mbetet i hapur**: controller-at e tjerë, entitetet e plota, DTO-të dhe testet e sigurisë ende kërkojnë inventar.


## 12. Auditimi i entiteteve dhe kufizimeve DB — faza 3 (2026-10-23)

**Burimi:** 8 klasa JPA (`Driver`, `Vehicle`, `TaxiCompany`, `User`, `Ride`, `DriverLocation`, `Document`, `DriverVehicleAssignment`) dhe 5 migrime Liquibase (`004`, `005`, `008`, `013`, `017`).

### 12.1 Marrëdhëniet e verifikuara

- `User` ka `role`, `status`, `phone`, `email`, `passwordHash`, `phoneVerified`; autentikimi lidhet me këtë entitet.
- `TaxiCompany` ka `owner` si `@OneToOne User`, fushat ligjore, licencën, gjendjen e verifikimit, pagesat e lejuara dhe zonën.
- `Driver` ka `@ManyToOne TaxiCompany`, të dhënat e patentës, gjendjen `status` dhe `availabilityStatus`. **Nuk ka ende `User` relation** — kjo është pengesa strukturore për login individual.
- `Vehicle` ka `@ManyToOne TaxiCompany`, targë unike, brand/model/vit/vende/tip, datat e skadimit, status.
- `DriverVehicleAssignment` ka lidhje `@ManyToOne Driver` dhe `Vehicle`, `active`, `assignedFrom`, `assignedUntil`.
- `Ride` lidhet me `RideRequest`, `User customer`, `TaxiCompany`, dhe opsionalisht `Driver` / `Vehicle`; ka statuse dhe timestamp-e të fazave, si edhe `finalAmount`.
- `DriverLocation` është `@OneToOne Driver`, me një rekord aktual për shofer; `latitude`, `longitude` dhe `locationUpdatedAt`. Nuk ruan në këtë entitet `accuracy`, `deviceTimestamp` ose historik pikash.
- `Document` ka `ownerType` + `ownerId` (lidhje polimorfike në nivel aplikacioni), tip, skedar, data, status verifikimi dhe arsye refuzimi. **Jo** foreign key direkte nga `ownerId` te tri tabelat — validimi i përkatësisë është përgjegjësi e shërbimeve.

### 12.2 Constraints të konfirmuara në migrimet e lexuara

| Migrimi | Kontrolli i integritetit |
|---|---|
| 004 | `drivers.company_id` FK; unique `phone`, `license_number` |
| 005 | `vehicles.company_id` FK; unique `plate_number` |
| 008 | `driver_locations.driver_id` FK dhe unique — një pozicion aktual për shofer |
| 013 | Unique `(ride_request_id, company_id)`, lejon tentativa te kompani të ndryshme |
| 017 | Partial unique index `active` për caktim shoferi dhe automjeti (një caktim aktiv secili) |
| 017 | Partial unique index për një ride aktiv për klientin dhe një ride aktiv për shoferin |
| 017 | Index `(company_id,status,availability_status)` për kërkimin e shoferëve |

**Gjetje e rëndësishme:** kufizimet e konkurrencës nuk mbështeten vetëm te Java; ka mbrojtje DB konkrete. Nuk duhet t'i heqim kur të krijojmë Driver App dhe API-të e reja.

### 12.3 Implikimet për arkitekturën mobile dhe pagesat

1. **Driver Identity:** krijimi i lidhjes `User–Driver` kërkon ADR/migrim additive dhe testim për të mos ndryshuar rekordet ekzistuese të kompanive.
2. **GPS:** modeli aktual `driver_locations` është pozicioni më i fundit, jo location-event log. Shtesat për `accuracy`, timestamp të pajisjes dhe mbrojtjen nga update-i i vjetër duhet të bëhen pa prishur klientin web.
3. **Financat:** `Ride.finalAmount` ekziston; në këtë entitet nuk ka `paymentStatus`, `commission` apo `invoice`. Duhet analizuar modeli financiar i veçantë, jo shtuar automatikisht komisioni në secilin tranzicion të ride.
4. **Dokumentet:** `ownerType+ownerId` kërkon teste negative specifike për aksesin ndërmjet kompanive dhe për pronësinë e dokumentit.
5. **Invariante:** `uk_active_ride_per_customer`, `uk_active_ride_per_driver`, `uk_active_assignment_per_driver`, `uk_active_assignment_per_vehicle` janë elemente që duhen ruajtur dhe testuar.

### 12.4 Mbetet hapur

- Verifikimi i 20 migrimeve të tjera dhe krahasimi i skemës finale me të gjitha entitetet.
- Inventar i plotë i DTO-ve dhe endpoint-eve të 24 controller-ave.
- Auditimi i zbatimit real të authorization për të gjitha operacionet.
- Ekzekutimi i suite-ve të testeve dhe prova për implementimin aktual.
- Miratimi i inventarit final.

**Status:** `MRT-00-04` mbetet `[ ]`, megjithëse fazat 1–3 janë dokumentuar.
