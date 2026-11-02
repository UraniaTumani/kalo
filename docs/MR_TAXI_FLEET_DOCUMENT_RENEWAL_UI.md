# Partner fleet document renewal UI

Existing driver/vehicle document cards offer renewal for REJECTED documents or
recorded expiry dates before today in Europe/Tirane. A new file and expiry date
are required; issue date cannot be future. File size/format checks are advisory;
the server remains authoritative. Confirmation freezes the chosen document ID,
file and fields. No automatic upload retry; after success/failure, current and
history queries are invalidated to resolve a possibly committed upload.

History includes previous decisions and authenticated downloads by version ID.
Old evidence remains retained. The latest ID is not inferred from UI ordering;
the current-list API owns that choice. A current-list error disables renewal.
Changing owner remounts the card and clears its form; in-flight uploads still
belong to the original owner URL. Do not mistake navigation for request rollback.

English/Albanian; desktop/mobile mocked tests join the existing CI browser config,
including the previous initial-upload regression tests. No backend, DB, date-field
synchronization or approval-based ONLINE enforcement changes in this patch.

Validation from frontend on owner environment:

    npm run build
    npm run lint
    npx playwright test --config playwright.invitations.config.ts

No execution result is claimed before running these commands.
