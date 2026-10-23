# MR TAXI — MASTER PRODUCT & ENGINEERING CHECKLIST

**Version:** 1.0 (baseline 2026-10-23)  
**Repository:** `UraniaTumani/kalo`  
**Baza e analizuar:** `feature/f42-real-gps-freshness`  
**Status:** AKTIV — dokument bazë në branch-in `feature/f42-real-gps-freshness`; jo i integruar në `main`.  
**Pronësia:** MR TAXI project owner.  

> **Qëllimi:** një burim i vetëm për çdo punë të MR TAXI. Nuk është projekt nga zero; ruan Spring Boot modular monolith, PostgreSQL/Liquibase, React web, dokumentet F12 dhe ride lifecycle ekzistues. Customer App dhe Driver App janë aplikacione të dedikuara iOS/Android.

## Si përdoret

- `[x]` = verifikuar me prova. `[ ]` = e hapur, edhe nëse ka kod fillestar; nuk e shënojmë done pa kriteret e pranimit.
- **P0** = e domosdoshme për MVP/pilot të sigurt; **P1** = optimizim dhe zgjerim i afërt; **P2** = fazë e mëvonshme. P0 nuk nënkupton se çdo task ka të njëjtën urgjencë: ndiqen varësitë.
- Për çdo task të kryer, shto nën të: `Evidence: PR/commit, test command/result, date, reviewer` dhe përditëso checkbox-in në të njëjtin commit/PR.
- Task-et që varen nga ligji duhen validuar për **Shqipërinë dhe juridiksionin operativ**, jo kopjuar pa dallim nga Uber/Lyft/Bolt/FREENOW.
- Mos fshi historikun. Nëse kërkesa ndryshon, krijo ADR dhe changelog, pastaj ndrysho task-un përkatës.
- Përpara ndryshimeve në repo, branch, DB ose mjedise reale kërkohet miratimi i pronarit.

## Kriteri i përgjithshëm Definition of Done (DoD)

Një item shënohet `[x]` **vetëm kur**: (1) kërkesa dhe edge cases janë të qarta; (2) kodi dhe migrimet përkatëse janë integruar në branch-in e synuar; (3) compile/lint/testet e nevojshme kalojnë; (4) authorization/tenant isolation janë testuar kur preken; (5) UI mobile/desktop dhe gabimet janë verifikuar kur ka UI; (6) ndryshimi dhe evidenca janë dokumentuar; (7) nuk ka regresione kritike të pambyllura; (8) është miratuar nga pronari sipas procesit.

## Vendime të qëndrueshme të produktit

- **Marketplace i kompanive të licencuara:** klienti zgjedh kompaninë, ajo cakton shoferin e saj.
- **Pagesa fillestare:** çmimi final sipas taksimetrit; klienti paguan cash/POS direkt te shoferi/kompania. MR TAXI regjistron komisionin B2B dhe e faturon periodikisht. **100 lekë/udhëtim është hipotezë për t’u validuar**.
- **4 sipërfaqe:** Customer App, Driver App, Partner Web, Admin Web. Driver App ka login të vet dhe GPS native; browser-i nuk është zgjidhja finale për GPS background.
- **Një backend:** Spring Boot modular monolith; PostgreSQL + Liquibase; pa mikroshërbime të panevojshme.
- **Parim:** Company, Driver dhe Vehicle kanë eligibility të ndara; statusi ONLINE nuk provon GPS aktiv/fresh.
- **Nuk premtojmë** ETA rrugore nga Haversine, vendndodhje GPS të pandërprerë nga OS ose pagesa të arkëtuara pa integrim real.

## A. Baseline e verifikuar — evidencë historike, jo certifikim production

- [x] **V-01** Repository monorepo ekziston me Spring Boot modular monolith dhe frontend React.  
  Evidence: README.md, pom.xml, frontend/package.json.
- [x] **V-02** Ekziston skema `DocumentType` me 2 kategori kompani, 2 shoferi dhe 5 automjeti.  
  Evidence: frontend/src/lib/api/types.ts.
- [x] **V-03** UI i dokumenteve F12 ka upload dhe shkarkim për shoferët dhe automjetet.  
  Evidence: FleetDocumentsCard.tsx; testet E2E/role kërkojnë ri-ekzekutim.
- [x] **V-04** Ekziston model ride me REQUESTED→DRIVER_ASSIGNED→DRIVER_ARRIVING→DRIVER_ARRIVED→IN_PROGRESS→COMPLETED.  
  Evidence: README.md / Main API Flows.
- [x] **V-05** Testimi lokal konfirmoi dërgim GPS real iPhone → backend → PostgreSQL.  
  Evidence: Testi i përshkruar në bisedë; driver_id=1.
- [x] **V-06** U realizua testimi i një ride demo #5 dhe u pa statusi COMPLETED.  
  Evidence: Testi i përshkruar në bisedë.
- [x] **V-07** U implementua UI “Rifillo GPS” në branch-in F42, me build/typecheck të suksesshëm.  
  Evidence: feature/f42-real-gps-freshness, commit a0063f8; funksionimi pas klikimit kërkon ri-test.

**Kufizim:** disa README përmbajnë përshkrime të vjetruara, p.sh. dokumentet dhe testet. Nuk janë konsideruar të verifikuara në prodhim pa një auditim dhe ri-ekzekutim të testeve.

## B. Backlog i detajuar me kritere pranimi

### 00 — Governance dhe burimi i së vërtetës

- [x] **MRT-00-01 · P0** — Publikojmë këtë checklist si dokument të versionuar në repo (`docs/MR_TAXI_MASTER_CHECKLIST.md`).  
  **Pranimi:** Skedari është në branch-in e miratuar dhe ka link të qëndrueshëm.  
  **Evidence:** `docs/MR_TAXI_MASTER_CHECKLIST.md` në `feature/f42-real-gps-freshness`, 2026-10-23.
- [ ] **MRT-00-02 · P0** — Krijojmë PRD unik me aktorë, udhëtime, rregulla biznesi dhe kërkesa jo-funksionale.  
  **Pranimi:** PRD miratohet dhe çdo kërkesë ka ID.
- [ ] **MRT-00-03 · P0** — Përcaktojmë kufijtë e produktit: marketplace kompanish, kompania cakton shoferin, taksimetër, pagesë direkte.  
  **Pranimi:** Vendimet dhe përjashtimet shkruhen në PRD.
- [ ] **MRT-00-04 · P0** — Hartojmë inventarin real të moduleve, endpoints, DTO-ve, entiteteve, migrimeve dhe UI-ve.  
  **Pranimi:** Inventari lidhet me skedarët ekzistues.
- [ ] **MRT-00-05 · P0** — Auditim i testeve reale në commit-in bazë: unit, integration, E2E, build, lint.  
  **Pranimi:** Regjistrohen komandat, numrat e testeve, rezultati, SHA dhe data.
  **Evidencë e pjesshme (2026-10-23):** `./mvnw.cmd test` në Windows 11 / Java 17.0.12 / Maven 3.9.16, branch `feature/f42-real-gps-freshness` (local ishte i sinkronizuar me `dabf011` para testit): **443 tests, 0 failures, 0 errors, 0 skipped, BUILD SUCCESS**, 03:39 min. PostgreSQL 17 Testcontainers, `test` profile, Liquibase 31 changesets. **Frontend (2026-10-23, pas sinkronizimit në `92d57d7`):** `npm run build` kaloi (`tsc -b && vite build`, 2153 module, ~1.19s), Vite paralajmëroi për chunk `index` ~615.05 kB >500 kB. `npm run lint` kaloi me **0 errors, 22 warnings** (`oxlint`, 98 files, 116 rules); paralajmërime për React hooks, purity `Date.now()` gjatë render-it, ref mutation gjatë render-it, Fast Refresh, dhe dependencies. **E2E (2026-10-23, pas fast-forward në `d74a774`):** `npm run test:e2e` kaloi: **124 passed, 0 failed, 6.4m**, Chrome desktop, 1 worker; Docker Compose `kalo-e2e` krijoi PostgreSQL të izoluar dhe backend healthy, me mesazhin final `tearing the stack down`. **Mbetet `[ ]`: konfirmimi i SHA me `git rev-parse HEAD`, ruajtja/verifikimi i raporteve të testeve, dhe klasifikimi i 22 warnings të lint.**
- [ ] **MRT-00-06 · P0** — Përditësojmë README-t dhe heqim mospërputhjet me kodin aktual.  
  **Pranimi:** Dokumentacioni përshkruan saktë dokumentet, i18n, testet dhe GPS-in.
- [ ] **MRT-00-07 · P0** — Krijojmë ADR për arkitekturën modular-monolith, mobile, tracking dhe financat.  
  **Pranimi:** Çdo vendim ka alternativa, arsyetim, pasoja dhe status.
- [ ] **MRT-00-08 · P0** — Vendosim rregullin Done dhe template të issue/PR me testet e pranimit.  
  **Pranimi:** Çdo task ka owner, acceptance, evidence, commit dhe review.
- [ ] **MRT-00-09 · P1** — Përcaktojmë KPI për rezervimin dhe performancën operacionale.  
  **Pranimi:** Maten conversion, accept time, completion, cancellations, GPS freshness.
- [ ] **MRT-00-10 · P1** — Hartojmë plan release me milestone të verifikueshëm dhe kapacitet real.  
  **Pranimi:** Varësitë dhe kriteret e daljeve janë dokumentuar.

### 01 — Rregullat e biznesit dhe modelet

- [ ] **MRT-01-01 · P0** — Specifikojmë rolet CUSTOMER, DRIVER, PARTNER_OWNER, DISPATCHER, ADMIN dhe lejet.  
  **Pranimi:** Matrica RBAC miratohet dhe testohet.
- [ ] **MRT-01-02 · P0** — Përcaktojmë marrëdhënien User–Driver–Company dhe kushtet për kalim kompanie.  
  **Pranimi:** Modeli dhe migrimet përputhen me përdoruesit ekzistues.
- [ ] **MRT-01-03 · P0** — Përcaktojmë përgjegjësinë e statusit të udhëtimit: kompania, shoferi, admin.  
  **Pranimi:** Tabela e tranzicioneve tregon aktorin dhe kushtet.
- [ ] **MRT-01-04 · P0** — Ruajmë një model unik të udhëtimit për web dhe mobile.  
  **Pranimi:** Të gjitha kanalet përdorin të njëjtat shërbime biznesi.
- [ ] **MRT-01-05 · P0** — Ndajmë `availability`, `rideStatus`, `locationFreshness`, `paymentStatus`.  
  **Pranimi:** Asnjë gjendje nuk ngatërrohet ose inferohet pa rregull.
- [ ] **MRT-01-06 · P0** — Përcaktojmë kufizimet e një udhëtimi aktiv për klientin/shoferin.  
  **Pranimi:** Teste konkurrence dhe partial indexes mbajnë invariantet.
- [ ] **MRT-01-07 · P1** — Politikat për refuzim, timeout, anulim, no-show dhe rikaktim.  
  **Pranimi:** Çdo rrugë ka rezultat dhe përgjegjësi të përcaktuar.
- [ ] **MRT-01-08 · P1** — Politikat për shërbim ndërqytetas, rezervime të ardhshme dhe kategori taksish.  
  **Pranimi:** Skenarët vlerësohen dhe planifikohen pa prishur MVP.

### 02 — Onboarding i kompanisë dhe compliance

- [ ] **MRT-02-01 · P0** — Validim i checklist-it ligjor për taksitë në Shqipëri me këshilltar/autoritetet përkatëse.  
  **Pranimi:** Burimet, juridiksioni dhe data e verifikimit ruhen.
- [ ] **MRT-02-02 · P0** — Ruajmë regjistrimin e kompanisë me emër ligjor, NIPT, kontakt, adresë, licencë.  
  **Pranimi:** Formulari dhe backend-i validuar.
- [ ] **MRT-02-03 · P0** — Ruajmë upload-in e `BUSINESS_REGISTRATION` dhe `TAXI_LICENSE`.  
  **Pranimi:** Ngarkim, download privat dhe status verifikimi testohen.
- [ ] **MRT-02-04 · P0** — Shtojmë verifikim për përfaqësuesin ligjor dhe të drejtën e përfaqësimit kur duhet.  
  **Pranimi:** Politikë e minimizimit të ID-ve dhe prova të dokumentuara.
- [ ] **MRT-02-05 · P0** — Përcaktojmë kushtet për `APPROVED`, `SUSPENDED`, `REJECTED` dhe riaplikim.  
  **Pranimi:** Backend bllokon rezervime për partnerët jo të përshtatshëm.
- [ ] **MRT-02-06 · P0** — Krijojmë checklist onboarding progresive me dokumente të munguar dhe gjendje.  
  **Pranimi:** Partneri sheh çfarë duhet të plotësojë dhe pse.
- [ ] **MRT-02-07 · P1** — Shtojmë kontakt financiar dhe të dhëna faturimi B2B me kontroll autorizimi.  
  **Pranimi:** Përfaqësuesi mund t’i përditësojë dhe admin t’i auditojë.
- [ ] **MRT-02-08 · P1** — Verifikojmë licencimin sipas qytetit/zonës dhe datave të vlefshmërisë.  
  **Pranimi:** Kontrolli parametrizohet sipas juridiksionit.
- [ ] **MRT-02-09 · P1** — Rishikojmë oraret, zonat e shërbimit dhe mënyrat e pagesës.  
  **Pranimi:** Kërkimi përjashton kompani të papërshtatshme.

### 03 — Shoferët dhe Driver Identity

- [ ] **MRT-03-01 · P0** — Ruajmë CRUD/statuset ekzistuese të shoferëve dhe dokumentet F12.  
  **Pranimi:** Regression dhe autorizimi partner testohen.
- [ ] **MRT-03-02 · P0** — Ndërtojmë login individual të shoferit, lidhur me `Driver` ekzistues.  
  **Pranimi:** Driver futet pa përdorur llogarinë e kompanisë.
- [ ] **MRT-03-03 · P0** — Autorizojmë update GPS dhe statuse vetëm për shoferin/udhëtimin e tij.  
  **Pranimi:** Teste negative për id të huaj dhe kompani tjetër.
- [ ] **MRT-03-04 · P0** — Dokumente shoferi: patentë + certifikatë profesionale, me status verifikimi.  
  **Pranimi:** Skadimi/pezullimi bllokon eligibility sipas politikës.
- [ ] **MRT-03-05 · P0** — Shtojmë verifikim identiteti dhe foto profili të përshtatshme për shfaqje.  
  **Pranimi:** Identiteti përputhet me dokumentet dhe respektohet privatësia.
- [ ] **MRT-03-06 · P0** — Përcaktojmë kuptimin e `ONLINE`, `BUSY`, `OFFLINE` dhe kohën e sesionit të punës.  
  **Pranimi:** Rregullat mbeten konsistente edhe në disconnect.
- [ ] **MRT-03-07 · P1** — Rregulla për shoferë me shumë kompani, ftesa dhe revokim aksesi.  
  **Pranimi:** Nuk ka dy sesione kontradiktore pa politikë të qartë.
- [ ] **MRT-03-08 · P1** — Përcaktojmë checks për dokumente mjekësore/penale vetëm kur kërkohen me ligj.  
  **Pranimi:** Nuk ruhen kopje sensitive pa bazë dhe afat.
- [ ] **MRT-03-09 · P1** — Historik i ndryshimeve të përkatësisë dhe i statusit të shoferit.  
  **Pranimi:** Admin mund të auditojë aktorin, arsyen dhe datën.

### 04 — Automjetet, caktimet dhe dokumentet

- [ ] **MRT-04-01 · P0** — Ruajmë CRUD/statuset për automjete, asignimet dhe dokumentet F12.  
  **Pranimi:** Regresion dhe kontroll multi-tenant.
- [ ] **MRT-04-02 · P0** — Validim targë, brand/model, vit, vende, kategori dhe skadimet.  
  **Pranimi:** Input invalid refuzohet në API dhe UI.
- [ ] **MRT-04-03 · P0** — Dokumentet ekzistuese të mjetit: regjistrim, sigurim, pasagjerë, kontroll teknik, taksimetër.  
  **Pranimi:** Upload/approve/expiry verifikohen për secilin tip.
- [ ] **MRT-04-04 · P0** — Verifikim i mbulimit të sigurimit për transport pasagjerësh kundrejt pagesës.  
  **Pranimi:** Nuk mjafton vetëm një emërtim gjenerik sigurimi.
- [ ] **MRT-04-05 · P0** — Kontroll eligibility për çdo çift shofer–automjet para ONLINE/caktimit.  
  **Pranimi:** Test për automjet ose dokument të skaduar.
- [ ] **MRT-04-06 · P1** — Shtojmë VIN, ngjyrë, pronar ligjor, prova përdorimi dhe leje të veçantë taksie kur kërkohet.  
  **Pranimi:** Fushat lidhen me regjistrimin e automjetit dhe dokumentet.
- [ ] **MRT-04-07 · P1** — Mbështetje për automjet rezervë dhe rikaktim me historik.  
  **Pranimi:** Ndryshimet gjatë udhëtimit ruhen dhe komunikohen.
- [ ] **MRT-04-08 · P1** — Kalendari i skadimit 30/14/7 ditë dhe rinovimi i dokumentit.  
  **Pranimi:** Njoftimet dhe ndryshimi i eligibility testohen.
- [ ] **MRT-04-09 · P1** — Versionim dhe auditim dokumentesh në vend të fshirjes pa gjurmë.  
  **Pranimi:** Historiku i vendimeve dhe ndryshimeve mbetet i verifikueshëm.

### 05 — Customer App iOS/Android dhe UX

- [ ] **MRT-05-01 · P0** — Zgjedhim React Native dhe provojmë feasibility në të dy platformat.  
  **Pranimi:** Prototip API, push dhe background GPS vlerësohet.
- [ ] **MRT-05-02 · P0** — Design system i përbashkët: typography, ngjyra, aksesueshmëri, lokalizim.  
  **Pranimi:** Ekranet kryesore kalojnë checklist UX/a11y.
- [ ] **MRT-05-03 · P0** — Onboarding klienti, login, rikuperim dhe profile personale.  
  **Pranimi:** Funksionon me auth ekzistues dhe test E2E.
- [ ] **MRT-05-04 · P0** — Harta + pickup manual/GPS + destinacion + rikonfirmim pin-i.  
  **Pranimi:** Pika e gabuar korrigjohet lehtë.
- [ ] **MRT-05-05 · P0** — Kërkimi shfaq një ofertë për kompani dhe vetëm kompani të përshtatshme.  
  **Pranimi:** Saktësi e rezultateve dhe status i qartë no-results.
- [ ] **MRT-05-06 · P0** — Kartë kompanie: kategori, disponueshmëri, vlerësim dhe informacion transparent mbi taksimetrin.  
  **Pranimi:** Asnjë çmim/ETA e shpikur.
- [ ] **MRT-05-07 · P0** — Rezervim, pritje përgjigjeje, timeout/refuzim dhe kompani tjetër.  
  **Pranimi:** Flow i plotë me ekrane gabimi dhe retry.
- [ ] **MRT-05-08 · P0** — Ride live me identitet shoferi, targë, model, pozicion, freski dhe ETA kur ka provider.  
  **Pranimi:** UI nuk shfaq pozicion të vjetër si live.
- [ ] **MRT-05-09 · P0** — Anulim me konfirmim; historik, përmbledhje dhe rating.  
  **Pranimi:** Gjendjet fundore nuk zhduken nga UI.
- [ ] **MRT-05-10 · P1** — Adresat e ruajtura, udhëtimet e fundit dhe booking më i shpejtë.  
  **Pranimi:** Flow i përsëritur me pak ndërveprime.
- [ ] **MRT-05-11 · P1** — Share trip me kontakt të besuar, me kufizim aksesi dhe afat.  
  **Pranimi:** Linku skadon dhe nuk zbulon të dhëna të panevojshme.
- [ ] **MRT-05-12 · P1** — Kontaktim i shoferit dhe qendër ndihme për ride.  
  **Pranimi:** Privatësi e numrit dhe raportim i kontrolluar.
- [ ] **MRT-05-13 · P1** — Rezervim për një orë të ardhshme vetëm pas projektimit të kapacitetit dispatch.  
  **Pranimi:** Teste për ndryshime dhe anulime.
- [ ] **MRT-05-14 · P2** — Pagesë in-app vetëm me provider të licencuar dhe politikë payout/refund.  
  **Pranimi:** Integrimi i verifikuar për Shqipërinë.

### 06 — Driver App dhe GPS native

- [ ] **MRT-06-01 · P0** — Ndërtojmë skeleton Driver App, auth, sesion të sigurt dhe profil pune.  
  **Pranimi:** Build Android dhe iOS në pajisje.
- [ ] **MRT-06-02 · P0** — Onboarding leje location dhe shpjegim i qartë i përdorimit.  
  **Pranimi:** Lejet nuk kërkohen para kohe.
- [ ] **MRT-06-03 · P0** — Implementojmë location tracking native në background, sipas kufizimeve OS.  
  **Pranimi:** Teste foreground, background, lock screen.
- [ ] **MRT-06-04 · P0** — Online/Offline dhe GPS lidhen me sesion pune, jo me ekranin aktiv.  
  **Pranimi:** Refresh/navigation nuk humb gjendjen e serverit.
- [ ] **MRT-06-05 · P0** — Update koordinatash me accuracy, deviceTimestamp, serverTimestamp dhe dedupe.  
  **Pranimi:** Fix i vjetër nuk mbishkruan fix të ri.
- [ ] **MRT-06-06 · P0** — Trajtim offline, reconnect, permission revoked, low battery dhe app killed.  
  **Pranimi:** UI tregon saktë disponueshmërinë; nuk premton tracking të pamundur.
- [ ] **MRT-06-07 · P0** — Push për udhëtim të caktuar dhe deep link në udhëtim.  
  **Pranimi:** Njoftimi arrin kur app është në background sipas OS.
- [ ] **MRT-06-08 · P0** — Ekrane: assigned → arriving → arrived → start → complete.  
  **Pranimi:** Çdo veprim kalon backend state machine.
- [ ] **MRT-06-09 · P0** — Formular final taksimetri + metodë pagese + konfirmim.  
  **Pranimi:** Rregulla idempotence dhe validim server-side.
- [ ] **MRT-06-10 · P0** — Navigim në hartë/app hartash dhe mënyrë e qartë për rikthim.  
  **Pranimi:** Shoferi vazhdon kontrollin e udhëtimit pa humbur statusin.
- [ ] **MRT-06-11 · P1** — Foto/profil, historik turnesh, udhëtime dhe përmbledhje fitimesh.  
  **Pranimi:** Vetëm të dhënat e autorizuara.
- [ ] **MRT-06-12 · P1** — Përdorim inteligjent i baterisë dhe metrika të cilësisë së GPS-it.  
  **Pranimi:** Matje në pajisje reale, jo interval fiks si garanci.

### 07 — Ride lifecycle, dispatch dhe realtime

- [ ] **MRT-07-01 · P0** — Ruajmë ride state machine dhe mekanizmat ekzistues të konkurencës.  
  **Pranimi:** Teste rollback, race dhe API contracts.
- [ ] **MRT-07-02 · P0** — Specifikojmë kush vendos secilin tranzicion dhe çfarë audit eventi krijohet.  
  **Pranimi:** Matrica API per role aprovohet.
- [ ] **MRT-07-03 · P0** — Migrimi i kontrolleve nga partner web drejt Driver App pa dyfishuar logjikën.  
  **Pranimi:** Partner/Driver përdorin shërbime të njëjta tranzicioni.
- [ ] **MRT-07-04 · P0** — Idempotency keys për accept/start/complete/payment record.  
  **Pranimi:** Retry nuk dyfishon udhëtim ose faturim.
- [ ] **MRT-07-05 · P0** — Timeout dhe përgjigje e kontrolluar kur partneri/driveri nuk përgjigjet.  
  **Pranimi:** Customer sheh alternativë të qartë.
- [ ] **MRT-07-06 · P0** — Ride events të auditueshme me actor, timestamp, old/new state.  
  **Pranimi:** Hetimi i çdo tranzicioni është i mundur.
- [ ] **MRT-07-07 · P0** — Rregulla stale-GPS për eligibility dhe shfaqjen e hartës.  
  **Pranimi:** Driver online me GPS të vjetër nuk promovohet si live.
- [ ] **MRT-07-08 · P1** — Shtojmë push për njoftime kritike, evente live ku justifikohet; polling fallback.  
  **Pranimi:** Nuk humbasin tranzicionet pas disconnect.
- [ ] **MRT-07-09 · P1** — PIN për nisjen e udhëtimit dhe politika e përjashtimeve.  
  **Pranimi:** Testohet PIN i saktë, gabim dhe skadim.
- [ ] **MRT-07-10 · P1** — Rikaktim i kontrolluar shoferi me informim të klientit.  
  **Pranimi:** Një shofer aktiv për ride dhe histori të plotë.
- [ ] **MRT-07-11 · P2** — Auto-dispatch opsional brenda flotës, fillimisht determinist.  
  **Pranimi:** Renditje e shpjegueshme dhe override manual.

### 08 — Tarifat, pagesat dhe komisionet

- [ ] **MRT-08-01 · P0** — Konfirmojmë modelin: taksimetër, pagesë direkte cash/POS, komision B2B ndaj kompanisë.  
  **Pranimi:** PRD dhe termat tregtarë e përshkruajnë saktë.
- [ ] **MRT-08-02 · P0** — Përcaktojmë çfarë është udhëtim i faturueshëm dhe trajtimin e anulimeve/no-show.  
  **Pranimi:** Politikë versionuar dhe teste.
- [ ] **MRT-08-03 · P0** — Ndajmë Ride COMPLETED nga payment status dhe commission status.  
  **Pranimi:** Gjendjet nuk ngatërrohen në API/databazë.
- [ ] **MRT-08-04 · P0** — Shuma e taksimetrit futet nga shoferi ose kompania sipas rolit dhe politikës.  
  **Pranimi:** Validohet shuma/monedha/metoda dhe actor-i.
- [ ] **MRT-08-05 · P0** — Rregulla për korrigjime fare me audit, arsye dhe autorizim.  
  **Pranimi:** Nuk mbishkruhet historia pa gjurmë.
- [ ] **MRT-08-06 · P0** — Ledger i pandryshueshëm për komisionin për ride, jo vetëm kolonë balance.  
  **Pranimi:** Një komision unik për ride; reversals si entries të reja.
- [ ] **MRT-08-07 · P0** — Raporte për kompani: udhëtime, bruto e deklaruar, komisione, korrigjime.  
  **Pranimi:** Shumat rakordohen me ledger.
- [ ] **MRT-08-08 · P0** — Faturim periodik B2B, afatet, statusi paguar/pa paguar dhe rakordim bankar.  
  **Pranimi:** Admin verifikon pagesën; eksport i auditueshëm.
- [ ] **MRT-08-09 · P0** — Verifikojmë tatimet, faturimin dhe modelin e arkëtimit me kontabilist/jurist shqiptar.  
  **Pranimi:** Përcaktohen TVSH, e-faturimi, përgjegjësitë.
- [ ] **MRT-08-10 · P1** — Kujtesa për pagesat, mosmarrëveshje, kredit-note dhe pezullim i kontrolluar.  
  **Pranimi:** Politikat testohen, ride aktive nuk prishen.
- [ ] **MRT-08-11 · P1** — Monitorim i anomalive të shumave pa pretenduar që GPS provon taksimetrin.  
  **Pranimi:** Alerts të verifikueshme dhe review manual.
- [ ] **MRT-08-12 · P2** — Provider marketplace payouts për pagesë online, vetëm kur tregu e mbështet.  
  **Pranimi:** KYC/AML, refund, settlement dhe procesimi testohen.

### 09 — Siguria, privatësia dhe besimi

- [ ] **MRT-09-01 · P0** — RBAC dhe tenant isolation në çdo endpoint me company/driver/ride id.  
  **Pranimi:** Testet negative ndërmjet kompanive kalojnë.
- [ ] **MRT-09-02 · P0** — Kontroll i lidhjes actor–driver–ride për GPS dhe statuset mobile.  
  **Pranimi:** Nuk lejohet spoof për driver tjetër.
- [ ] **MRT-09-03 · P0** — Secure token storage/rotation/revocation për mobile.  
  **Pranimi:** Logout, device loss dhe 401 trajtohen.
- [ ] **MRT-09-04 · P0** — Rate limiting, brute-force, audit access dhe secrets nga env/secret store.  
  **Pranimi:** Security regression + konfigurim staging.
- [ ] **MRT-09-05 · P0** — Private object storage, upload validation, antivirus/file limits, signed download.  
  **Pranimi:** Dokumentet nuk janë publikë dhe aksesohen me autorizim.
- [ ] **MRT-09-06 · P0** — Data minimization dhe retention për GPS, ID dhe dokumente.  
  **Pranimi:** Politika me afate e bazë ligjore e dokumentuar.
- [ ] **MRT-09-07 · P0** — Privatësia/GDPR kur aplikohet dhe ligji shqiptar; termat, njoftimet dhe të drejtat.  
  **Pranimi:** Review ligjor dhe UX consent.
- [ ] **MRT-09-08 · P0** — Monitorim dhe reagim ndaj incidenteve, backup dhe audit trail.  
  **Pranimi:** Procedurë e testuar në staging.
- [ ] **MRT-09-09 · P1** — Foto, targa, identifikimi dhe raportim incidenti në aplikacion.  
  **Pranimi:** Klienti sheh informacionin e saktë të shoferit.
- [ ] **MRT-09-10 · P1** — Share-trip/PIN dhe kanale të sigurta kontakti.  
  **Pranimi:** Test revokimi dhe kufizimesh.
- [ ] **MRT-09-11 · P1** — Vlerësim penetrimi dhe dependency vulnerability checks.  
  **Pranimi:** Gjetjet kritike mbyllen para pilotit.

### 10 — Paneli Partner, Admin dhe suporti

- [ ] **MRT-10-01 · P0** — Auditim i funksioneve ekzistuese dashboard, rides, drivers, vehicles dhe documents.  
  **Pranimi:** Matrica implementim/test dhe screenshots.
- [ ] **MRT-10-02 · P0** — Partner onboarding me wizard dhe indikator verifikimi.  
  **Pranimi:** Çdo hap rikthehet pas logout; gabimet janë të qarta.
- [ ] **MRT-10-03 · P0** — Dispatch board mobile-friendly me udhëtime aktive dhe prioritete.  
  **Pranimi:** Kërkesa e re është e dukshme dhe mund të caktohet shpejt.
- [ ] **MRT-10-04 · P0** — Admin review për kompani, shoferë, automjete dhe dokumente.  
  **Pranimi:** Arsye refuzimi/approve ruhet me actor.
- [ ] **MRT-10-05 · P0** — Support i lidhur me ride, partner dhe customer pa ekspozim të tepërt.  
  **Pranimi:** Gjurmim kërkese deri në zgjidhje.
- [ ] **MRT-10-06 · P1** — KPI reale për disponueshmëri, pranim, përfundim, anulime, ankesa.  
  **Pranimi:** Data të vërteta, përkufizime të shkruara.
- [ ] **MRT-10-07 · P1** — Njoftime skadimi, faturimi dhe kërkesash me prioritete.  
  **Pranimi:** Jo njoftime të përsëritura pa nevojë.
- [ ] **MRT-10-08 · P1** — UI responsive, gjuhë shqip + anglisht, empty/error/loading states.  
  **Pranimi:** Teste UX në telefon dhe desktop.
- [ ] **MRT-10-09 · P1** — Raportet financiare të kompanisë dhe eksportet.  
  **Pranimi:** Totalet rakordohen me backend ledger.

### 11 — Maps, ETA, performance dhe observability

- [ ] **MRT-11-01 · P0** — Rishikojmë hartat dhe provider geocoding për përdorim komercial.  
  **Pranimi:** API key, quota, TOS, caching dhe failover dokumentohen.
- [ ] **MRT-11-02 · P0** — Nuk shfaqim distancën Haversine si ETA rrugore.  
  **Pranimi:** UI etiketon metrikat saktë.
- [ ] **MRT-11-03 · P1** — Routing/ETA me rrugë reale për ardhjen e shoferit.  
  **Pranimi:** Test i kohës dhe distancës me skenarë realë.
- [ ] **MRT-11-04 · P0** — Dashboard për ride timeouts, GPS stale, dështime API, push dhe billing.  
  **Pranimi:** Alarmet dallojnë dështimet nga përdorimi normal.
- [ ] **MRT-11-05 · P0** — Log-e strukturuara, correlation-id dhe redaktim i të dhënave sensitive.  
  **Pranimi:** Një ride ndiqet në log pa ekspozuar token/ID.
- [ ] **MRT-11-06 · P1** — Performancë me ngarkesë test për kërkim, koordinata dhe rezervime.  
  **Pranimi:** Objektiva SLO/P95 të dakorduara.
- [ ] **MRT-11-07 · P2** — PostGIS/Redis vetëm pas profile/benchmark dhe dokumentimi ADR.  
  **Pranimi:** Ka prova matjesh që justifikojnë kompleksitetin.

### 12 — Testet, infrastruktura dhe release

- [ ] **MRT-12-01 · P0** — Pipeline CI për build backend/frontend, testet unit dhe integration.  
  **Pranimi:** PR i pasuksesshëm bllokohet.
- [ ] **MRT-12-02 · P0** — Testcontainers PostgreSQL për invariants, Liquibase dhe konkurencë.  
  **Pranimi:** Testet kalojnë në CI, jo vetëm lokal.
- [ ] **MRT-12-03 · P0** — E2E për customer → company → driver → complete → ledger.  
  **Pranimi:** Udhëtim i plotë në staging.
- [ ] **MRT-12-04 · P0** — Teste fizike iOS/Android për permissions, GPS, network loss dhe push.  
  **Pranimi:** Matrica pajisje/OS/evidence ruhet.
- [ ] **MRT-12-05 · P0** — Mjedise të ndara dev, test, staging, prod me secrets të veçanta.  
  **Pranimi:** Asnjë demo seeder në DB reale.
- [ ] **MRT-12-06 · P0** — Migrime backward-compatible, backup/restore i testuar dhe rollback.  
  **Pranimi:** Restore provë dokumentohet.
- [ ] **MRT-12-07 · P0** — Konfigurim domain/TLS, DNS, monitoring, rate limits dhe backups.  
  **Pranimi:** Runbook operational i testuar.
- [ ] **MRT-12-08 · P0** — Release gates: security, E2E, performance baseline, dokumente ligjore.  
  **Pranimi:** Checklist release firmoset para pilotit.
- [ ] **MRT-12-09 · P1** — Pilot i kontrolluar me kompani, shoferë dhe klientë realë.  
  **Pranimi:** Mblidhen KPI dhe incidentet; vendim go/no-go.
- [ ] **MRT-12-10 · P1** — App Store/Play Store compliance, privacy declarations dhe review.  
  **Pranimi:** Publikimet kalojnë review pa premtime të rreme GPS.

## C. Përmbledhje progresi

- **Baseline të verifikuara:** 7 `[x]` (janë prova ekzistence/funksioni, jo përfundim i detyrave të reja).
- **Detyra të hapura:** 130 (`P0`: 90, `P1`: 36, `P2`: 4).
- **Detyra të mbyllura nga backlog B:** 1/131; kjo është pikënisje konservatore që shmang deklarimin e gabuar të punëve si Done.

### Format i detyrës së përfunduar

```md
- [x] **MRT-XX-YY · P0** — Emërtimi i detyrës.
  **Pranimi:** ...
  **Evidence:** PR #..., commit abc123, tests ..., reviewed YYYY-MM-DD.
```

### Rendi i ekzekutimit (varësitë)

1. **Baseline:** 00 (specifikim, inventar, CI, verifikim real).
2. **Model biznesi/siguri:** 01, 02, 03, 04, 09 dhe 12 për API-të e sigurta.
3. **Driver App + GPS:** 06 bashkë me 07; testim fizik iOS dhe Android.
4. **Customer App:** 05 dhe 11; udhëtim E2E në dy pajisje.
5. **Finance + operim:** 08 dhe 10; rregullat e arkëtimit, faturimi, safety.
6. **Pilot/release:** 12; mbyllje e gjetjeve kritike.

### Regjistri i ndryshimeve

| Data | Versioni | Ndryshimi | Evidence / miratimi |
|---|---|---|---|
| 2026-10-23 | 1.0 | Krijimi dhe vendosja e master checklist në branch-in F42 | Miratuar në bisedë; MRT-00-01 |
