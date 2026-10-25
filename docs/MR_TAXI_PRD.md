# MR TAXI — PRODUCT REQUIREMENTS DOCUMENT (PRD)

**Versioni:** 0.1 — Draft për shqyrtim  
**Data:** 2026-10-23  
**Repository:** `UraniaTumani/kalo`  
**Branch bazë:** `feature/f42-real-gps-freshness`  
**Checklist:** `docs/MR_TAXI_MASTER_CHECKLIST.md` / `MRT-00-02`  
**Status:** DRAFT — kërkon miratim përpara se të shënohet e përfunduar.

> **Parimi themelor:** MR TAXI është marketplace për kompani taksish të licencuara. Klienti zgjedh kompaninë; kompania cakton shoferin. Ruajmë backend-in Spring Boot modular monolith, PostgreSQL/Liquibase, frontend-in React për Partner/Admin, dhe rrjedhën ekzistuese të rezervimeve. Ndërtojmë Customer App dhe Driver App iOS/Android mbi të njëjtin backend. Nuk e rindërtojmë projektin nga e para.

## 1. Qëllimi i produktit

MR TAXI lidh klientët me kompani taksish të verifikuara. Klienti kërkon taksi për një pikë nisjeje dhe destinacion, krahason kompanitë e disponueshme, rezervon te një kompani, mëson shoferin e caktuar dhe ndjek udhëtimin. Kompania administron flotën dhe operacionet. Shoferi ka aplikacion individual pune. MR TAXI administron marketplace-in dhe faturon kompanitë sipas marrëveshjes.

### Objektivat
- Rezervim i shpejtë, i parashikueshëm dhe transparent për klientin.
- Kompanitë të ruajnë kontroll mbi flotën, tarifimin me taksimetër dhe caktimin e shoferit.
- Shoferët të kenë aplikacion të besueshëm për punën në terren, përfshirë GPS native në background sipas kufizimeve të OS.
- Kontroll i qartë i dokumentacionit, statusit të flotës, sigurisë dhe gjurmueshmërisë financiare.
- Një burim i vetëm i së vërtetës për udhëtimet dhe pagesat.

### Jashtë MVP-së fillestare
- Nuk kërkohen pagesa online të klientëve në aplikacion.
- Nuk kërkohet algoritëm inteligjent auto-dispatch; kompania cakton manualisht.
- Nuk premtohen ETA rrugore pa shërbim routing real.
- Nuk premtohet tracking pa ndërprerje nga iOS/Android në çdo gjendje.
- Nuk krijohen microservices pa nevojë të provuar.

## 2. Aktorët dhe përgjegjësitë

| Aktori | Përgjegjësia | Kanalet |
|---|---|---|
| CUSTOMER | Kërkim, zgjedhje kompanie, rezervim, ndjekje udhëtimi, anulim, vlerësim | Customer App; web aktual si mjet testimi |
| DRIVER | Sesion pune ONLINE/OFFLINE, vendndodhje, marrje udhëtimesh, statuse, raportim shume | Driver App (për t'u ndërtuar) |
| PARTNER_OWNER | Regjistrim kompanie, flota, dokumente, caktim shoferi, operacionet dhe faturat B2B | Partner Web |
| DISPATCHER | Marrje dhe caktim rezervimesh sipas kompetencave të kompanisë | Partner Web; rol i planifikuar |
| ADMIN | Verifikim kompanish/dokumentesh, administrim, kontestime, mbështetje, financa | Admin Web |
| SYSTEM | Rregullat e statusit, eligibility, timeout, faturim, audit dhe njoftime | Backend |

**E hapur për vendim:** rolet reale të partnerit dhe dispatcher-it; cila pjesë e veprimeve operative do të mbetet e mundur nga Partner Web pasi Driver App të aktivizohet.

## 3. Rregullat e qëndrueshme të biznesit

**BR-01:** Klienti zgjedh kompaninë, jo një shofer individual. Një rezultat kërkimi paraqet një ofertë për çdo kompani të kualifikuar.

**BR-02:** Kompania zgjedh shoferin dhe automjetin nga flota e autorizuar. Vetëm çifte shofer–automjet të përshtatshme mund të caktohen.

**BR-03:** Shoferi dhe automjeti kanë eligibility të pavarur nga verifikimi i kompanisë. Një dokument i skaduar i një shoferi nuk pezullon automatikisht gjithë kompaninë.

**BR-04:** Disponueshmëria `ONLINE` nuk provon se GPS-i është i freskët. Shoferi pa koordinatë të vlefshme dhe të freskët nuk duhet të shfaqet si i disponueshëm për kërkime të reja.

**BR-05:** Çmimi final për MVP-në merret nga taksimetri. Klienti paguan cash ose POS direkt te shoferi/kompania. Shuma dhe metoda e pagesës regjistrohen veçmas nga statusi i udhëtimit.

**BR-06:** MR TAXI llogarit komision B2B sipas udhëtimit të faturueshëm dhe ia faturon periodikisht kompanisë. `100 lekë/udhëtim` është hipotezë biznesi, jo tarifë e miratuar dhe jo implementim i përfunduar.

**BR-07:** Një udhëtim i përfunduar nuk është provë se pagesa është arkëtuar. Statuset `ride`, `payment`, dhe `commission` duhet të jenë të ndara.

**BR-08:** Çdo veprim kritik ruhet me identitetin e aktorit, timestamp, ndryshimin e statusit dhe identifikuesin e kërkesës për audit.

**BR-09:** Një klient nuk ka dy udhëtime aktive konfliktuale; një shofer nuk ka dy udhëtime aktive konfliktuale. Backend-i enforce-on invariantet.

**BR-10:** Dokumentet janë private. Aksesi kufizohet sipas rolit dhe kompanisë, me verifikim të vlefshmërisë para eligibility.

## 4. Kërkesat funksionale — klienti

- **CUS-01 P0:** Regjistrim, login, menaxhim profili dhe rikuperim llogarie.
- **CUS-02 P0:** Pickup me GPS ose manualisht, destinacion dhe rikonfirmim të pikës.
- **CUS-03 P0:** Rezultatet paraqesin kompani të disponueshme, kategori automjeti, vlerësim dhe shpjegim të mënyrës së tarifimit.
- **CUS-04 P0:** Zgjedhje kompanie, rezervim dhe ekran pritjeje me rezultat pranim/refuzim/timeout.
- **CUS-05 P0:** Pas caktimit, shfaqen shoferi, automjeti, targa, statusi dhe pozicioni i freskët.
- **CUS-06 P0:** Anulim në gjendjet e lejuara, histori, përmbledhje dhe vlerësim.
- **CUS-07 P0:** UI shënon qartë kur GPS ose ETA nuk janë të besueshme.
- **CUS-08 P1:** Adresa të ruajtura, share-trip me skadim aksesi dhe ndihmë të lidhur me udhëtimin.
- **CUS-09 P1:** Rezervime të planifikuara pasi dispatch-i mbështet garantimin dhe anulimet.

## 5. Kërkesat funksionale — kompania dhe administratori

- **COM-01 P0:** Regjistrim dhe profil kompanie me emër ligjor, NIPT, kontakt, adresë, licencë.
- **COM-02 P0:** Ngarkim dhe verifikim dokumentesh `BUSINESS_REGISTRATION` dhe `TAXI_LICENSE`.
- **COM-03 P0:** CRUD shoferësh dhe automjetesh, dokumente dhe lidhje aktive shofer–automjet.
- **COM-04 P0:** Panel kërkesash, pranim/refuzim dhe caktim manual me timeout.
- **COM-05 P0:** Kontroll i kompanisë/zhvillimit për statusin e licencave, dokumenteve dhe skadimeve.
- **COM-06 P0:** Akses multi-tenant: kompania nuk mund të lexojë ose ndryshojë të dhënat e kompanisë tjetër.
- **COM-07 P0:** Admin miraton/refuzon/pezullon subjektet me arsye të dokumentuar.
- **COM-08 P1:** Dashboard operacional, njoftime për skadim, raporte dhe suport.
- **COM-09 P1:** Dispatcher i deleguar dhe caktim automatik opsional vetëm brenda flotës, pas MVP-së.

## 6. Kërkesat funksionale — shoferi

- **DRV-01 P0:** Llogari personale dhe login; Driver nuk autentikohet me llogarinë e kompanisë.
- **DRV-02 P0:** Shoferi sheh vetëm kompaninë/automjetin/udhëtimet për të cilat është i autorizuar.
- **DRV-03 P0:** Sesion pune, ONLINE/OFFLINE me kontroll të eligibility.
- **DRV-04 P0:** GPS native me leje të qarta dhe përdorim të arsyeshëm të baterisë.
- **DRV-05 P0:** Marrja e një caktimi me push notification dhe hapje të udhëtimit.
- **DRV-06 P0:** Ndryshim i statuseve arriving, arrived, start, complete përmes të njëjtës state machine të backend-it.
- **DRV-07 P0:** Në përfundim raportohet shuma e taksimetrit, valuta dhe mënyra e pagesës; backend kontrollon retry/idempotency.
- **DRV-08 P0:** Offline/disconnect/reconnect shfaqet me sinqeritet; nuk ndryshohet statusi i serverit pa konfirmim.
- **DRV-09 P1:** Historik udhëtimesh, informacion për turnin dhe navigim të integruar me aplikacionet e hartave.

## 7. Ride lifecycle dhe autoriteti

Gjendjet ekzistuese të lexuara nga dokumentacioni bazë:

```text
REQUESTED -> DRIVER_ASSIGNED -> DRIVER_ARRIVING -> DRIVER_ARRIVED
          -> IN_PROGRESS -> COMPLETED

REQUESTED -> DECLINED / NO_RESPONSE
pre-start -> CANCELLED (sipas politikës së anulimit)
```

| Tranzicioni | Aktori aktual | Objektivi për mobile | Server-side |
|---|---|---|---|
| Search/select | Customer | Customer | Kushte disponueshmërie + transaksione |
| Accept + assign | Partner | Partner/dispatcher | Verifikim flote dhe konkurrencë |
| DRIVER_ARRIVING | Partner në MVP web | Driver | Autorizim i assignment |
| DRIVER_ARRIVED | Partner në MVP web | Driver | Status paraardhës valid |
| IN_PROGRESS | Partner në MVP web | Driver + opsional PIN | Validim i gjendjes dhe aktorit |
| COMPLETED | Partner në MVP web | Driver, me mbikëqyrje kompanie | Fare required, idempotency dhe audit |
| CANCELLED | Customer (brenda kufizimeve) | Customer/mbështetje për përjashtime | Politika anulimi |

**Për t'u vendosur:** nëse duhet një `DRIVER_ACCEPTED` eksplicit, PIN i detyrueshëm dhe proces i veçantë për no-show/rikaktim. Mos shto statuse pa ADR dhe testet e tranzicioneve.

## 8. GPS dhe vendndodhja

- **LOC-01:** `location` duhet të ruajë ose interpretojë accuracy, device timestamp, server timestamp dhe burimin e përditësimit.
- **LOC-02:** Përditësimet e vjetra/të dyfishta nuk duhet të mbishkruajnë një pozicion më të ri.
- **LOC-03:** `location freshness` është koncept më vete nga statusi `ONLINE`.
- **LOC-04:** GPS aktiv vetëm gjatë një sesioni pune/udhëtimi të autorizuar dhe me lejet e OS.
- **LOC-05:** Klienti shikon vetëm pozicionin e shoferit të caktuar për udhëtimin aktiv dhe vetëm sipas politikës së privatësisë.
- **LOC-06:** Në mungesë sinjali, sistemi nuk premton gjurmim live ose ETA të saktë.
- **LOC-07:** Browser tracking në `useDriverTracking.ts` mbetet mjet testimi/mbështetjeje, jo zgjidhje production për background location.

## 9. Dokumentacioni dhe eligibility

**Kategori dokumentesh që ekzistojnë në klientin aktual:**
- Company: `BUSINESS_REGISTRATION`, `TAXI_LICENSE`.
- Driver: `DRIVING_LICENSE`, `PROFESSIONAL_CERTIFICATE`.
- Vehicle: `VEHICLE_REGISTRATION`, `INSURANCE`, `PASSENGER_INSURANCE`, `TECHNICAL_INSPECTION`, `TAXIMETER_VERIFICATION`.

**Propozime për auditim, jo kërkesa universale ligjore:** identitet i përfaqësuesit, autorizim për përfaqësim, identitet/foto e shoferit, VIN/ngjyrë e automjetit, leje taksie specifike për mjetin, verifikim mbulimi transporti pasagjerësh.

Rregullat duhet të jenë të bazuara në Shqipëri dhe juridiksionin konkret. Dokumentet veçanërisht sensitive të mos ruhen nëse një verifikim i statusit/licencës mjafton.

## 10. Pagesat dhe të ardhurat e MR TAXI

**PAY-01 P0:** Shuma e taksimetrit regjistrohet në fund të udhëtimit; futja është manuale në MVP.

**PAY-02 P0:** Metodat fillestare: `CASH` dhe `CARD_IN_CAR` — pagesa merret nga shoferi/kompania. Regjistrimi i metodës nuk është provë automatike e arkëtimit.

**PAY-03 P0:** `paymentStatus` ka gjendje të ndara nga `rideStatus`. Zgjedhja e statusit final të pagesës kërkon specifikim të detajuar.

**PAY-04 P0:** Fare mund të korrigjohet vetëm me procedurë të autorizuar dhe me audit të ndryshimit.

**PAY-05 P0:** Vetëm udhëtimet që plotësojnë rregullat e komisionit krijojnë `commission entry` unik.

**PAY-06 P0:** Kompania merr pasqyrën periodike me udhëtime, komisione, korrigjime, kontestime dhe detyrimin neto.

**PAY-07 P0:** Pagesa e komisioneve ndaj MR TAXI rakordohet veçmas me faturën; mungesa e pagesës trajtohet me proces B2B dhe paralajmërime.

**PAY-08 P1:** Kontestimet e çmimit dhe faturimit zgjidhen me evidencë, kufizim të roleve dhe pa fshirje të historikut.

**PAY-09 P2:** Pagesa elektronike e klientit në app vetëm pasi të vërtetohet provider-i i licencuar, disponibiliteti në Shqipëri, settlement, refund dhe autorizimet.

## 11. Siguria dhe privatësia

- **SEC-01:** JWT me skadim/refresh dhe kontroll statusi i përdoruesit, pa sekrete development në production.
- **SEC-02:** Autorizimi për çdo driver ID dhe ride ID nuk mbështetet te ID-ja e dërguar nga klienti.
- **SEC-03:** Izolim i të dhënave sipas kompanisë në API dhe në të gjitha listimet.
- **SEC-04:** Dokumente private, ruajtje e sigurt, skanim/validim tipi/madhësie dhe log aksesesh.
- **SEC-05:** Minimizim i të dhënave GPS, afat mbajtjeje, politika fshirjeje dhe audit i aksesit.
- **SEC-06:** Rate limiting, njoftime të sigurta, log pa dokumente ose token-e të ekspozuara.
- **SEC-07:** Raportim incidenti, suport, autentikim i fortë administrativ dhe rikthim nga gabimet.

## 12. UX dhe aksesueshmëria

- **UX-01:** Rezervim i qartë me pak hapa dhe rikthim të kuptueshëm nga gabimet.
- **UX-02:** Klienti mund të korrigjojë lehtë pickup, edhe kur GPS jep pozicion të pasaktë.
- **UX-03:** Shfaqje transparente e taksimetrit; asnjë çmim/ETA e fabrikuar.
- **UX-04:** Në harta dallohen statusi i udhëtimit, freskia GPS dhe ETA.
- **UX-05:** Shqip si gjuhë e parë; anglisht dhe gjuhë të tjera sipas nevojës.
- **UX-06:** Kontrast, madhësi të shtypjes, screen readers, loading/empty/error states.
- **UX-07:** Partneri sheh qartë dokumentet që mungojnë dhe hapat e aktivizimit.
- **UX-08:** Shoferi mund të kryejë detyrat kryesore me pak ndërveprime, pa ndërprerje të navigimit.

## 13. Kërkesat jo-funksionale (NFR)

- **NFR-01 — Besueshmëri:** asnjë pagesë/komision/ride i dyfishuar pas retry; idempotency në endpoint-et kritike.
- **NFR-02 — Disponueshmëri:** timeout dhe failure recovery të qarta. SLO konkret vendoset pas matjes së pilotit.
- **NFR-03 — Siguria e të dhënave:** backup i testuar me restore, secrets në environment dhe autorizim multi-tenant.
- **NFR-04 — Observability:** logs të strukturuara, metrics për timeout/udhëtime aktive/GPS stale, alarme operacionale.
- **NFR-05 — Performancë:** matje e p95 të kërkimit, caktimit, GPS ingestion, jo optimizim i verbër.
- **NFR-06 — Testueshmëri:** unit/integration/E2E për ride, security, documents, finance dhe mobile.
- **NFR-07 — Migrime:** ndryshime additive dhe të kthyeshme ku është e mundur; Liquibase është burimi i skemës.
- **NFR-08 — Përputhshmëri:** politika privatësie, dokumente, taksimetra, sigurime dhe faturim kontrollohen për tregun shqiptar përpara pilotit.

## 14. Hartë e gjendjes ekzistuese dhe e punës

| Aftësia | Gjendja e konfirmuar | Veprimi |
|---|---|---|
| Spring Boot, JPA, Liquibase, PostgreSQL | Kod ekzistues | Ruajmë; auditojmë |
| React Partner/Admin/Customer Web | Kod ekzistues | Ruajmë web dhe ripërdorim kontratat |
| Ride lifecycle | API/states në kod/dokumentim | Regression; zgjerojmë autorizimin për shofer |
| Company/Driver/Vehicle + dokumente | 9 kategori në `types.ts`, upload UI ekziston | Auditim eligibility/verifikimi |
| GPS nga iPhone Safari | U testua deri te PostgreSQL | Mjet testimi; Driver App native kërkohet |
| Driver App individual | Ende jo në kodin e verifikuar | Ndërtojmë |
| Customer App individual | Ende jo në kodin e verifikuar | Ndërtojmë |
| B2B commission ledger dhe invoicing | Nuk është konfirmuar si implementim | Projektojmë |
| Full regression në commit aktual | Ende pa ekzekutim të ri | MRT-00-05 |

## 15. Kriteret e MVP dhe pilotit

Një pilot me kompani reale mund të propozohet vetëm kur:
1. Një kompani reale regjistrohet dhe verifikohet me dokumentet e duhura.
2. Regjistrohen shoferi dhe automjeti; eligibility bllokon dokumente të pavlefshme.
3. Shoferi hyn me llogarinë e tij nga Driver App dhe transmeton GPS sipas lejeve të pajisjes.
4. Klienti rezervon në Customer App dhe zgjedh kompaninë.
5. Kompania cakton shoferin dhe shoferi kryen statuset e plota.
6. Klienti ndjek udhëtimin me GPS freshness transparente.
7. Regjistrohet taksimetri/metoda e pagesës dhe një komision unik B2B.
8. E2E mbulon timeout/refuzim/anulim/offline/retry.
9. Dokumentet/pozicioni janë të kufizuar sipas rolit, privatësisë dhe afateve.
10. Backup/restore, monitorimi dhe suporti janë testuar.

## 16. Vendime të hapura që kërkojnë miratim

| ID | Vendimi | Alternativat | Propozimi fillestar |
|---|---|---|---|
| DEC-01 | Kush e kontrollon përfundimin | Shoferi / kompania / të dy | Shoferi, me override të audituar nga kompania |
| DEC-02 | Statusi i pagesës CASH | Vetëdeklarim / klient konfirmon / rakordim | Vetëdeklarim, i dalluar nga pagesa e verifikuar |
| DEC-03 | Komisioni | 100 lekë flat / përqindje / subscription | Pilot flat me kushte dhe fatura B2B |
| DEC-04 | Driver acceptance | Push dhe konfirmim / vetëm caktim | Konfirmim nga shoferi, me timeout; status i ri vetëm pas ADR |
| DEC-05 | PIN i udhëtimit | Në çdo ride / opsional | Pilot si kontroll sigurie, pas analizës UX |
| DEC-06 | Operator eligibility | Dokumente minimum / verifikim më i thelluar | Politikë sipas juridiksionit |
| DEC-07 | Mobile framework | React Native / native | React Native si kandidat; proof-of-concept GPS native |
| DEC-08 | Komisioni kur klienti anulon | Tarifo / mos tarifo / sipas fazës | Mos tarifo pa shërbim të përfunduar për MVP |

---

**Lidhja me checklistën:** `MRT-00-02` mbetet [ ] derisa PRD të shqyrtohet, vendimet e hapura të trajtohen dhe pronari ta miratojë. `MRT-00-03` mbetet [ ] derisa vendimet e bazës së biznesit të miratohen. 
