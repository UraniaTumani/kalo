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

There is deliberately **no fallback JWT secret**. A committed secret that works
in production is a committed production credential, so the application fails
fast instead. The `dev` profile supplies a throwaway one for local work.

Generate a secret:

```bash
openssl rand -base64 48
```

---

## 5. Running the backend

Production-style run (secret from the environment):

```bash
JWT_SECRET="$(openssl rand -base64 48)" ./mvnw spring-boot:run
```

On Windows PowerShell:

```powershell
$env:JWT_SECRET = "REPLACE_WITH_BASE64_SECRET"; ./mvnw spring-boot:run
```

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

It creates one admin, one customer, two approved and active taxi companies
(ABC Taxi and City Taxi), two drivers and two vehicles per company with active
assignments, all drivers `ONLINE` with fresh positions around Tirana, both
companies open 24/7 with a 25 km service area. A dev-only scheduled job keeps
those positions fresh, so taxi search keeps returning results while you work.

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

| Role | Phone (username) | Password |
| --- | --- | --- |
| Admin | `+355690000001` | `Admin123!` |
| Customer | `+355690000002` | `Customer123!` |
| Partner — ABC Taxi | `+355690000003` | `Partner123!` |
| Partner — City Taxi | `+355690000004` | `Partner123!` |

These exist only under the `dev` profile and are safe to publish precisely
because they cannot be seeded anywhere else.

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
