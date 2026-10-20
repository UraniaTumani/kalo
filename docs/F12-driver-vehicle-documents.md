# F12 — Driver & Vehicle Documents

Branch: `feature/f12-driver-vehicle-documents`. No merge into main.

## Delivered

- Documents action in both desktop rows and mobile cards on Drivers and Vehicles.
- Shared owner-specific panel with document type, PDF/JPG/PNG file, optional number and issue/expiry dates.
- Frontend checks for empty files, supported extension/MIME, 5 MiB limit, 100-character document number and expiry preceding issue. Backend byte validation remains authoritative.
- Lists document type, number, dates, verification status and rejection reason. Existing types cannot be uploaded twice; backend conflicts are displayed.
- Authenticated API downloads as attachments using temporary blob URLs; storage references are never used as public links.
- Separate query keys and a remounted form when switching owners prevent cross-owner document/form reuse.
- Loading, retry, upload success and API error feedback; Albanian and English translations.
- Existing creation, availability, location, vehicle editing/reactivation/deactivation and pagination preserved.
- Local storage rejects any active `prod` profile, including `prod,dev`. No database, migrations, Docker volumes or payments changed.

## Checks in the implementation environment

- `npm ci --no-audit --no-fund`: passed.
- `npm run build`: passed (includes TypeScript). Existing large-chunk warning remains.
- `npm run lint`: passed with warnings in existing files, none in the new component.
- Standalone TypeScript check of the document browser tests/config: passed.
- `git diff --check`: passed.
- Java unit tests attempted with `./mvnw -Dtest=DriverDocumentServiceTest,VehicleDocumentServiceTest,LocalDocumentStorageTest test`: blocked before compilation because Maven Central DNS resolution failed. Previously reported passing tests were not revalidated here; the new mixed-profile test is unverified.
- `npm run test:documents`: four fixture-backed browser scenarios prepared (driver/vehicle at 375/1280px). Execution blocked at browser launch: no Chromium installed; Playwright browser download failed. These tests have not passed here.

## Final verification in IntelliJ / Windows

1. Preserve any local uncommitted work before switching. In IntelliJ Terminal:

   ```powershell
   git fetch origin
   git switch feature/f12-driver-vehicle-documents
   git pull --ff-only origin feature/f12-driver-vehicle-documents
   git status -sb
   .\mvnw.cmd "-Dtest=DriverDocumentServiceTest,VehicleDocumentServiceTest,LocalDocumentStorageTest" test
   ```

   These are unit tests; no database or Docker is required. A successful run must include the mixed `prod,dev` storage rejection test.

2. Frontend checks without a database:

   ```powershell
   cd frontend
   npm ci
   npm run build
   npm run lint
   npx playwright install chromium
   npm run test:documents
   ```

   The isolated Playwright configuration starts Vite on port 5175 and intercepts the API. It does not start Docker, run migrations or contact the real backend. It verifies upload multipart/authentication, invalid file rejection, listing, duplicate prevention, private attachment download, owner switching and horizontal overflow at both viewport sizes. Browser availability is required.

3. With your already configured DEV backend and existing local database, sign in as a partner. Do not change database configuration, migrations or volumes for this check. Start the frontend normally (`npm run dev`) and visit Drivers / Vehicles.

4. Click Documents on an existing driver. Upload a genuine PDF for Driving licence and a JPG/PNG for Professional certificate. Check number/dates/status, success feedback and downloaded bytes. Refresh and reopen the panel to confirm persistence. Repeat the five vehicle types on an existing vehicle.

5. Check a zero-byte file, unsupported file, file larger than 5 MiB, number longer than 100 characters and expiry before issue. A file renamed to `.pdf` with non-PDF contents must be refused by the backend. A second upload of an existing type must be blocked. Choose another type to continue.

6. Switch between two drivers and between two vehicles: file selection and errors must reset, and documents must match the selected owner. Repeat at 375px and desktop widths. Check existing create, edit, status and pagination actions still work.

7. Using a second partner account, try the first partner's document list/download endpoints. Expect denial (404/403), with no file bytes. Check a missing file and expired session produce appropriate errors, never a public file link. Fixture tests do not prove server-side tenant isolation.

8. Keep this feature in DEV/test. Local files default to `.local/document-uploads` and must remain private. PROD uploads/downloads require a separately configured durable storage implementation. Do not activate PROD for manual file tests.

## Remaining limits

End-to-end persistence, real file contents, server-side tenant isolation and the final visual pass require the local DEV setup. No document replacement/deletion API exists in F12; the UI does not offer it. Uploaded document dates do not automatically change driver licence or vehicle expiry fields.
