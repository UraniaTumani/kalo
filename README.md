# KALO Backend

KALO is a **taxi-company marketplace**, not a taxi operator.

> This repository holds both halves: the Spring Boot API at the root, and the
> React frontend in [`frontend/`](frontend/README.md).

```
Customer  ->  KALO  ->  Taxi Company  ->  Driver
```

The customer searches for taxis, sees one offer **per taxi company**, and picks
the company they want. That company then assigns one of its own drivers. KALO
brokers the introduction and nothing more:

- the **customer chooses a company**, not an individual driver
- the **company assigns the driver** from its own fleet
- the **customer pays the driver directly** — KALO processes no payments
- the **final price is the real taximeter amount**, entered by the company when
  the ride is completed

Distances shown during search are straight-line (Haversine) distances between
the driver and the pickup point. They are **not** routed ETAs.

---

## 1. Architecture

A single Spring Boot application organised as a **modular monolith**. Each
business area is a package containing its own controller / service / repository
/ entity / DTO layers:

| Module | Responsibility |
| --- | --- |
| `auth` | Registration, login, JWT issuing, user details |
| `user` | User entity, roles, account status |
| `partner` | Taxi company profile, verification, operating hours, service area, payment settings |
| `document` | Company verification documents |
| `driver` | Drivers and their availability |
| `vehicle` | Vehicles |
| `assignment` | Driver ↔ vehicle assignments |
| `location` | Driver GPS positions |
| `ride` | Ride search, offers, ride lifecycle, timeout scheduler |
| `rating` | Ride ratings and rating aggregates |
| `admin` | Partner verification, users, companies, rides |
| `common` | Shared entity base, exceptions, error contract, utilities |
| `config` | Security, OpenAPI |
| `dev` | Dev-profile demo data seeding |

Cross-cutting choices:

- **Liquibase** owns the schema; JPA runs with `ddl-auto=validate` and never
  modifies it.
- **Stateless JWT** authentication; no server-side sessions.
- **Pessimistic row locks** on the ride lifecycle, backed by **PostgreSQL
  partial unique indexes** for the invariants that must hold under concurrency.

The frontend in `frontend/` is a React 19 + TypeScript SPA (Vite, TanStack
Query, Tailwind, Leaflet) covering all three surfaces. Its dev server proxies
`/api` to port 8080, so the API needs no CORS configuration. See
[`frontend/README.md`](frontend/README.md).

---

## 2. Requirements

- Java 17
- Maven (use the bundled `./mvnw` wrapper)
- PostgreSQL 14+
- Node.js 20+ (frontend only)
- Docker (only needed for the integration tests)

---

## 3. PostgreSQL setup

```bash
createdb kalo_db
```

Or with Docker:

```bash
docker run -d --name kalo-postgres -e POSTGRES_DB=kalo_db -e POSTGRES_USER=postgres -e POSTGRES_PASSWORD=postgres -p 5432:5432 postgres:16-alpine
```

Liquibase creates every table on first startup. Do not create tables by hand.

---

## 4. Environment variables

| Variable | Required | Default | Purpose |
| --- | --- | --- | --- |
| `JWT_SECRET` | **yes** | *(none)* | Base64, at least 256 bits. The application refuses to start without it. |
| `JWT_EXPIRATION` | no | `3600000` | Token lifetime in milliseconds |
| `DB_URL` | no | `jdbc:postgresql://localhost:5432/kalo_db` | JDBC URL |
| `DB_USERNAME` | no | `postgres` | Database user |
| `DB_PASSWORD` | no | `postgres` | Database password |
| `SPRING_PROFILES_ACTIVE` | **in every deployment** | *(none)* | Must be set explicitly — there is no default profile. See below. |
| `CORS_ALLOWED_ORIGINS` | only if split-origin | *(empty)* | Comma-separated exact origins, e.g. `https://app.kalo.al`. Wildcards are rejected at startup. |
| `SWAGGER_ENABLED` | no | `false` | API documentation is off unless a deployment opts in. |
| `APP_RATE_LIMIT_TRUST_FORWARDED_FOR` | behind a proxy | `false` | Trust `X-Forwarded-For` for rate-limit identity. Enable only when a proxy sets it, or callers can forge it. |
| `APP_RATE_LIMIT_ENABLED` | no | `true` | Leave on. |
| `VITE_API_URL` | frontend build, split-origin only | *(empty)* | Baked into the bundle at build time. Empty means same-origin. |

There is deliberately **no fallback JWT secret** and **no default profile**.

A committed secret that works in production is a committed production
credential, so the application fails fast instead. The `dev` profile supplies a
throwaway one for local work.

`spring.profiles.default` was removed for the same reason. With it set to `dev`,
a deployment that configured `DB_URL` but forgot the profile would have started
against the production database using this repository's throwaway secret, and
seeded demo accounts whose passwords are printed below — silently, because the
application started normally. **Every deployment must set
`SPRING_PROFILES_ACTIVE` explicitly.**

`DevelopmentSafetyGuard` is the backstop. The application refuses to start when
the development secret is used without the `dev` profile, or when either the
development secret or demo seeding is pointed at a non-local database.

Generate a secret:

```bash
openssl rand -base64 48
```

---

## 5. Running the backend

`./mvnw spring-boot:run` activates the `dev` profile automatically — that is
configured in the POM rather than as a global default — so local work needs no
environment variables:

```bash
./mvnw spring-boot:run
```

An IDE run configuration does not read that POM setting. Set
`SPRING_PROFILES_ACTIVE=dev` in the run configuration once.

Production-style run, with an explicit profile and a real secret:

```bash
SPRING_PROFILES_ACTIVE=prod JWT_SECRET="$(openssl rand -base64 48)" ./mvnw spring-boot:run
```

### The whole stack, the way production is shaped

```bash
docker compose up --build
```

Frontend on <http://localhost:8081>, with nginx forwarding `/api` to the
backend so everything is one origin. PostgreSQL is published on `5433` for
`psql` and the IDE.

---

## 6. Running with the dev profile and demo data

The `dev` profile supplies a throwaway JWT secret and seeds demo data, so no
environment variables are needed:

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

The seeder is guarded twice — the `dev` profile **and**
`app.dev.seed.enabled=true` — and skips entirely if the demo admin already
exists. It never runs in production.

It creates one admin, three customers, two partners each owning a taxi company,
five drivers, five vehicles with active assignments, positions around Tirana,
operating hours, payment methods and a few completed rides with ratings — the
full list is in *Demo credentials* below. A dev-only scheduled job keeps the
seeded positions fresh, so taxi search keeps returning results while you work.

Everything is written through repositories. No production service is called and
none of them know the seeder exists, so the real business flow is unchanged and
no security rule is bypassed — the seeded rows are what the normal flow would
have produced. To start over, drop the database and let Liquibase rebuild it.

---

## 7. Running the tests

```bash
./mvnw clean test
```

Integration tests use **Testcontainers PostgreSQL**, so Docker must be running.
H2 is deliberately not used: it would not exercise the Liquibase migrations or
the PostgreSQL partial unique indexes the concurrency invariants depend on.

> **Status:** the Testcontainers dependencies and build wiring are in place; the
> integration test suite itself is still to be written. See
> *Known limitations* below.

---

## 8. Swagger / OpenAPI

With the application running:

- Swagger UI — <http://localhost:8080/swagger-ui.html>
- OpenAPI JSON — <http://localhost:8080/v3/api-docs>

To call a secured endpoint from Swagger: `POST /api/v1/auth/login`, copy the
`accessToken` from the response, click **Authorize**, and paste it (Swagger
adds the `Bearer ` prefix itself).

---

## 9. Demo credentials (dev profile only)

The phone number is the username.

| Role | Name | Phone (username) | Password |
| --- | --- | --- | --- |
| Admin | Admin KALO | `+355690000001` | `Admin123!` |
| Customer | Ana Hoxha | `+355690000002` | `Customer123!` |
| Customer | Blerim Krasniqi | `+355690000005` | `Customer123!` |
| Customer | Elira Dervishi | `+355690000006` | `Customer123!` |
| Partner — ABC Taxi | Arben Marku | `+355690000003` | `Partner123!` |
| Partner — City Taxi | Sokol Leka | `+355690000004` | `Partner123!` |

These exist only under the `dev` profile and are safe to publish precisely
because they cannot be seeded anywhere else.

### What else the seed creates

**ABC Taxi** (NIPT `K12345678A`) — cash and card in car, approved and active.
**City Taxi** (NIPT `K87654321B`) — cash only, approved and active.
Both open 24/7 with a 25 km service area centred on Tirana, so a demo works at
any hour. Change a row in `company_operating_hours` to watch a company drop out
of search on its closed day.

Five drivers, each with a vehicle, an active assignment and a fresh position
around Tirana. Two are OFFLINE on purpose, so the availability rules are
visible: only the ONLINE three can be offered to a passenger.

| Company | Driver | Phone | Availability | Vehicle |
| --- | --- | --- | --- | --- |
| ABC Taxi | Ilir Balla | `+355691000001` | ONLINE | AA101TR — Skoda Octavia (standard) |
| ABC Taxi | Gent Prifti | `+355691000002` | ONLINE | AA102TR — VW Passat (standard) |
| ABC Taxi | Mirela Hasa | `+355691000003` | OFFLINE | AA103TR — Toyota Prius (electric) |
| City Taxi | Fatos Shehu | `+355691000004` | ONLINE | CT201TR — Mercedes E-Class (premium) |
| City Taxi | Lediana Cela | `+355691000005` | OFFLINE | CT202TR — Ford Tourneo (van) |

Four completed rides with ratings, so history, fares and rating aggregates are
not empty on a fresh database. All of them are terminal, so none blocks a demo
passenger from starting a new ride.

Drivers are seeded with no password and no login of their own: in this MVP the
taxi company drives the ride lifecycle on the driver's behalf, so a driver is a
fleet record rather than a user account.

---

## 10. Main API flows

### Guest (no account)

```
POST /api/v1/public/taxi-availability     -> companies available near a point
```

Read-only and open to anonymous callers. It creates **no** `RideRequest` and
**no** `RideOffer`, so a guest cannot fill those tables with rows that can never
become rides, and every invariant around rides is untouched. Because nothing is
persisted there is no offer id to select: booking needs an account, since a
`Ride` requires a real customer.

The response is deliberately narrower than the authenticated one — no driver
id, vehicle id or plate number — so the live position and identity of a
specific driver is never exposed to an unauthenticated caller.

Both this endpoint and the customer search run through the same
`TaxiAvailabilityFinder`, so a guest can never be shown a company that a
signed-in customer would not be offered.

### Customer

```
POST /api/v1/auth/register/customer
POST /api/v1/auth/login

GET  /api/v1/me                                    -> own profile (any role)
PUT  /api/v1/me                                    -> edit own name and email

POST /api/v1/rides/search                          -> ride request + one offer per company
POST /api/v1/rides/requests/{id}/select            -> pick a company, creates the ride
GET  /api/v1/rides/current
GET  /api/v1/rides/{rideId}
POST /api/v1/rides/{rideId}/cancel
GET  /api/v1/rides/history                         (paged)
POST /api/v1/rides/{rideId}/rating
```

### Partner

```
POST /api/v1/auth/register/partner
POST /api/v1/auth/login                            (works while PENDING)

GET/PUT /api/v1/partner/me
POST    /api/v1/partner/submit-verification
POST    /api/v1/partner/documents

POST    /api/v1/partner/drivers
POST    /api/v1/partner/vehicles
POST    /api/v1/partner/assignments
PATCH   /api/v1/partner/drivers/{id}/availability   -> ONLINE requires an active vehicle
PUT     /api/v1/partner/drivers/{id}/location

GET     /api/v1/partner/rides                       (paged, optional ?status=)
POST    /api/v1/partner/rides/{id}/accept           -> assigns a driver, driver becomes BUSY
POST    /api/v1/partner/rides/{id}/decline
POST    /api/v1/partner/rides/{id}/driver-arriving
POST    /api/v1/partner/rides/{id}/driver-arrived
POST    /api/v1/partner/rides/{id}/start
POST    /api/v1/partner/rides/{id}/complete         -> taximeter total, driver back ONLINE
```

### Admin

```
GET  /api/v1/admin/partners                        (paged)
GET  /api/v1/admin/partners/{id}
POST /api/v1/admin/partners/{id}/approve
POST /api/v1/admin/partners/{id}/reject
POST /api/v1/admin/partners/{id}/suspend
POST /api/v1/admin/partners/{id}/reactivate

GET  /api/v1/admin/users                           (paged, ?role= ?status=)
GET  /api/v1/admin/users/{id}
POST /api/v1/admin/users/{id}/suspend
POST /api/v1/admin/users/{id}/reactivate

GET  /api/v1/admin/rides                           (paged, ?status= ?companyId=)
GET  /api/v1/admin/rides/{id}
```

### Ride state machine

```
REQUESTED -> DRIVER_ASSIGNED -> DRIVER_ARRIVING -> DRIVER_ARRIVED
          -> IN_PROGRESS -> COMPLETED

REQUESTED -> DECLINED       (company refused)
REQUESTED -> NO_RESPONSE    (no answer within app.ride.company-response-timeout-seconds)
pre-start -> CANCELLED      (customer)
```

After `DECLINED` or `NO_RESPONSE` the underlying ride request returns to
`SEARCHING` if it has not expired, so the customer can pick another company. A
customer can have only one active ride at a time, and a company can be tried
only once per ride request.

---

## 11. Error contract

Every error uses the same body:

```json
{
  "status": 403,
  "error": "Forbidden",
  "message": "You do not have permission to access this resource",
  "path": "/api/v1/admin/users",
  "timestamp": "2026-09-11T18:20:31.412Z"
}
```

| Status | Meaning |
| --- | --- |
| 400 | Validation failure or invalid operation for the current state |
| 401 | Missing, invalid or expired token; bad credentials; suspended or disabled account |
| 403 | Authenticated but wrong role |
| 404 | Resource not found, or not owned by the caller |
| 409 | Conflict — duplicate, or an invariant already satisfied by another row |
| 500 | Unexpected error (details logged server-side only) |

Stack traces, SQL and internal details are never returned to clients.

---

## 12. Current MVP limitations

Known and intentional for this milestone:

- **No integration test suite yet.** Build wiring for Testcontainers is in
  place; the tests are the next task.
- **No routed ETA.** Search distance is straight-line only.
- **No online payments.** The customer pays the driver directly, in cash or by
  card in the car, and the final price is the taximeter amount.
- **No realtime transport.** No WebSockets or push notifications; clients poll
  `GET /api/v1/rides/current`.
- **No Redis or caching layer.** Search hits PostgreSQL directly.
- **No file storage.** Documents are stored as URLs; uploading is out of scope.
- **No phone verification.** `phoneVerified` exists but nothing sets it, and
  because the phone number is the login identifier, `PUT /api/v1/me` does not
  allow changing it.
- **The public availability endpoint is unauthenticated and unthrottled.** It
  is read-only and cheap, but it has no rate limiting; put one in front of it
  before exposing the API to the internet.
- **No advanced vehicle filtering** (vehicle class, seats, luggage, child
  seats), no passenger count, no promotions, no chat.
- **Driver has no login.** The taxi company drives the ride lifecycle on the
  driver's behalf.
- **Ratings are one-way.** Customers rate companies and drivers, not the
  reverse.

### Future improvements

Routed distance/ETA via a routing provider, PostGIS for geospatial queries once
the driver population grows, Redis for hot search state, WebSockets for live
ride updates, online payments and payouts, driver mobile app with its own
authentication, file upload for documents, SMS phone verification.
