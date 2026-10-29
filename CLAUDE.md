# CLAUDE.md — ngo-platform

India NGO management & donation SaaS. Authoritative spec: `docs/business/MASTER_SPEC.md` (copy of the original contract).
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
`scripts/seed-dataset.ps1` (+ `seed-campaigns.ps1`), described in `docs/testing/TEST_DATASET.md`.
E2E: test-profile, test-phone, test-notifications, test-campaigns.

## Dev accounts (Keycloak realm `ngo`, DEV ONLY)
platform.admin@ngo.test / DevAdmin#2026 · support@ngo.test / DevSupport#2026 · asha.admin@ngo.test / DevNgo#2026 ·
ravi.staff@ngo.test / DevStaff#2026 · meera.donor@ngo.test / DevDonor#2026
