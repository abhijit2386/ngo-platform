# CLAUDE.md — ngo-platform

Yogdaan (yogdaan.co.in; renamed from Seva Setu 2026-11-02): India NGO management & donation SaaS. Authoritative spec: `docs/business/MASTER_SPEC.md` (copy of the original contract).
Status & next steps: `IMPLEMENTATION_STATUS.md` (always read first, update after every work session).

## Layout
- `apps/mobile` Flutter (Riverpod 3, go_router 18, Dio, flutter_appauth PKCE, ARB l10n en/hi/mr)
- `shared/platform-commons` technical library only: errors, correlation id, JWT security, AccessGuard
  (membership → permission → entitlement), transactional outbox/inbox, audit, idempotency keys, S3 storage, money
- `services/*` Spring Boot 3.5 / Java 21, DB per service (Flyway `db/migration`, commons tables in `db/commons`)
- Local dev is container-free: native PostgreSQL + Keycloak + Mailpit (D:\tools), Eureka `services/discovery-server`,
  HTTP event transport, local file storage. `infrastructure/podman/ngo-infra.yaml` is kept as a reference for CI/cloud.
- `scripts/*.cmd|ps1` Windows dev workflow (toolchain in D:\tools)

## Rules (non-negotiable)
- Money: BigDecimal scale 2, never float. Financial history immutable (DB triggers); corrections = reversals.
- Every NGO-scoped endpoint: `guard.require(ngoId, PERMISSION)`; paid operations also `guard.requireOperational(ngoId)`.
- Cross-service: REST `/internal/**` (X-Internal-Token) or events via `EventPublisher` inside the business transaction.
  Consumers wrap handlers in `IdempotentConsumer.handle`. No PII in events — fetch via internal APIs.
- Enum values in JPQL: use derived queries or native SQL (no FQN enum literals).
- Never expose entities from controllers; use records.
- No hard-coded UI strings: add keys with `apps/mobile/tool/l10n_add.py`.

## Commands (Windows, from D:\ngo-platform\scripts)
- `setup.cmd` toolchain · `setup-local.cmd` (one-time native stack) · `build-backend.cmd` (mvn verify)
- `start-local.cmd` / `stop-local.cmd` (Postgres, Keycloak, Mailpit, Eureka + core services) · `start-emulator.cmd` · `run-mobile.cmd` (build+install) · `test-mobile.cmd`

## Test data rule
Every feature is built and tested with the standard dataset (>= 2 admins, 2 staff, 3 donors):
`scripts/seed-dataset.ps1` (+ `seed-campaigns.ps1`, `seed-donations.ps1`, `seed-expenses.ps1`, `seed-documents.ps1`, `seed-receipts.ps1`, `seed-csr.ps1`), described in `docs/testing/TEST_DATASET.md`.
E2E: test-profile, test-phone, test-notifications, test-campaigns, test-admin-limit, test-donations, test-expenses, test-documents, test-receipts, test-csr, test-uc, test-proposals, test-reports. Load: test-load-receipts (with `start-local.ps1 -ReceiptCopies 3`).
Never pipe or redirect the output of start-local.ps1 in helper commands (started services inherit the handle and the command never ends).
PC scripts that call psql must set `$env:PGPASSWORD = 'dev-only-password'` and pass `-w` (a password prompt hangs the runner).
Service-to-service calls: always `InternalClients.forBaseUrl(...)` (cached; one HttpClient per service). Never build a
`java.net.http.HttpClient` per call: each one keeps a selector thread alive (1,908 leaked threads, 2026-11-02).
Screenshots: every walkthrough adds its shots to docs/screenshots/<feature>-NN-<what-it-shows>.png (full size) and a
section in docs/screenshots/README.md (heading with date and user, one-line summary, <img ... width="270">).
Reports: reporting-service keeps no copies; it reads figures from the owning services' `/internal/v1/ngos/{id}/reports/...`
endpoints (donation-payment, receipt, campaign-project). NGO-wide reports = admins + Finance; others get "My report";
plan REPORTING_LEVEL BASIC/STANDARD/ADVANCED decides sections (see reporting `Access`). New report figures go in the
owning service's internal report endpoint, not in reporting-service SQL.
Memory: the emulator's commit grows ~12 GB a day; run-mobile.ps1 restarts it past 8 GB. Check memory with a
Get-Process sweep (threads + private bytes) before blaming a service.

## Configuration
All keys and passwords (e-mail SMTP, Google/Facebook sign-in, secrets, storage, payments) go in ONE file:
`config/yogdaan.env` (gitignored; template `config/yogdaan.env.example`). start-local.ps1 loads it; after changing
e-mail or Google/Facebook values run `scripts/configure-login.ps1` (applies them to Keycloak). Never commit real keys.

## Dev accounts (Keycloak realm `ngo`, DEV ONLY)
platform.admin@ngo.test / DevAdmin#2026 · support@ngo.test / DevSupport#2026 · asha.admin@ngo.test / DevNgo#2026 ·
ravi.staff@ngo.test / DevStaff#2026 · meera.donor@ngo.test / DevDonor#2026
