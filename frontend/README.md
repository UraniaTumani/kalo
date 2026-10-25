# KALO Frontend

Single-page app covering all three KALO surfaces — passenger, taxi company and
administrator — against the Spring Boot API in the repository root.

## Stack and why

| Choice | Reason |
| --- | --- |
| **React 19 + TypeScript + Vite** | The backend is a separate JWT-bearer API. A SPA fits that; Next.js SSR and server components would fight the auth model for no gain. |
| **TanStack Query** | The API has no WebSockets, so live ride state has to be polled. This handles polling, caching and invalidation instead of hand-rolled `useEffect`. |
| **React Router 7** | Role-based route guards, one app, three surfaces. |
| **Tailwind CSS 4** | CSS-first config, no design system to invent. UI primitives are hand-written in `components/ui` rather than pulled from a component library. |
| **React Hook Form + Zod** | Schemas mirror the backend DTO constraints, so users see errors before a round trip. |
| **Leaflet + OpenStreetMap** | Picking pickup/destination coordinates is unavoidable here, and this needs no API key or billing account. |

## Running

The dev server proxies `/api` to `http://localhost:8080`, so the app and the API
are same-origin and **the backend needs no CORS configuration**.

```bash
cd frontend && npm install && npm run dev
```

Open <http://localhost:5173>. Point the proxy somewhere else with
`VITE_API_TARGET=http://host:port npm run dev`.

Other scripts: `npm run build` (type-check + production build), `npm run lint`,
`npm run test:e2e`, `npm run test:regression`, and `npm run test:documents`.
`test:e2e` starts an isolated `kalo-e2e` Docker Compose stack: PostgreSQL on
55432, backend on 18080 and frontend preview on 5174. The script tears its
own stack down after the tests (including the temporary database).

## Structure

```
src/
  lib/api/        types.ts mirrors the backend DTOs; client.ts is the fetch
                  wrapper (bearer token, 401 handling, ApiError);
                  endpoints.ts is every call, grouped by audience
  auth/           AuthContext (token + current user), ProtectedRoute
  components/     AppLayout, MapPicker, StatusBadge, Pagination, ui primitives
  features/
    guest/        public landing page, no account needed
    profile/      own details, shared by all three roles
    auth/         login, registration (passenger and company)
    customer/     book a taxi, current ride, history, rating
    partner/      dashboard, rides, drivers, vehicles, assignments,
                  documents, settings, availability
    admin/        verification queue, companies, users, rides, support
    support/      help and support requests
```

## Notes on behaviour

- **Guests.** `/` is public: a visitor picks a point on the map (or uses their
  browser location) and sees which companies could serve it, via the read-only
  `POST /api/v1/public/taxi-availability`. Nothing is reserved and nothing is
  bookable, so every result ends at a sign-up prompt rather than a button. A
  signed-in user hitting `/` is redirected to their own surface.
- **Profile.** `/profile` is available to all three roles. Name and email are
  editable; the phone number is shown read-only because it is the login
  identifier and changing it needs a verification flow the backend does not
  have.
- **Auth.** The web app uses access and refresh tokens. Expired access tokens
  can be refreshed and the original request retried; a rejected refresh logs
  out the user. Backend authorization remains authoritative.
- **Roles.** Signing in sends you to your own surface. Landing on another
  role's route redirects you to yours instead of showing a dead end.
- **Polling.** `GET /rides/current` polls every 5s while the ride is live and
  stops on a terminal status. The partner ride queue polls every 10s, because
  `REQUESTED` rides time out server-side with nothing to push the change.
- **Finished rides.** `GET /rides/current` only returns non-terminal rides, so
  the last ride id is remembered locally and the page falls back to
  `GET /rides/{id}`. Without that, a ride that just completed or was declined
  would vanish from the screen instead of showing its outcome and the rating
  prompt.
- **Distances** shown in search are straight-line, matching the backend. They
  are not routed ETAs and are not labelled as such.
- **Code splitting.** Each role's screens load on demand, so Leaflet only
  reaches the browser on pages that show a map.

## Current implementation and verified baseline

- Playwright browser E2E tests exercise a real, isolated backend and PostgreSQL
  stack: **124 Chrome desktop tests passed on 2026-10-23**. Additional document
  and GPS freshness test files exist; native mobile behavior is not covered.
- Company, driver and vehicle document upload and private download are present;
  production private storage is still outstanding.
- Access-token refresh/logout and both **Albanian and English** UI copy exist.
- Partner web can report browser GPS and restart tracking; customer ride pages
  apply location freshness checks. This is **not reliable native background GPS**.
- Production frontend build passed, and lint reported 0 errors and 22 warnings;
  Vite warned about a bundle above 500 kB on 2026-10-23.

## Not built yet

- Native Customer and Driver apps, individual Driver login and native GPS in
  background remain future work.
- In-app payments and a commission ledger do not exist yet; cash/card collection
  is direct to the driver/company, based on the taximeter's fare.
- No routed road ETA; straight-line distance is not travel time.
