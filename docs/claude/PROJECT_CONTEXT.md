# Yogdaan - project context (keep short; updated 2 Nov 2026)

## Product
India NGO SaaS: NGO registration/verification, staff and roles, campaigns with photos and share pages, online
donations (mock gateway, money goes straight to each NGO), offline cash/cheque, 80G/standard receipts with a receipt
designer, campaign accounts (budget, expenses with bills, vendors, staff cash ledger, account sheet PDF/Excel),
document vault and meetings, subscriptions (Community, Growth, Professional, Enterprise; per-feature entitlements),
notifications (e-mail + in-app, en/hi/mr), CSR suite (Enterprise). Adults only (18+). Renamed from "Seva Setu" on
2 Nov 2026; domain yogdaan.co.in bought.

## Architecture
- apps/mobile: Flutter (Riverpod 3, go_router, Dio, PKCE via Keycloak), l10n en/hi/mr (~1,270 keys).
- services/* Spring Boot 3.5, Java 21, one PostgreSQL DB per service, Flyway; Eureka discovery; api-gateway
  (routes in gateway Routes.java). Services: identity, ngo-registry, subscription, donor, donation-payment, receipt,
  campaign-project (campaigns, accounts, CSR UCs and proposals), document (vault, meetings, CSR packs),
  notification. Not started: ngo-operations (volunteers, beneficiaries), helpdesk, reporting.
- shared/platform-commons: errors, JWT security, AccessGuard (membership -> permission -> entitlement), outbox/inbox
  events over HTTP, audit, storage (local files now, S3-compatible later), money.
- Local stack on the laptop (no containers): PostgreSQL, Keycloak :8180 (realm ngo), Mailpit :8025, gateway :8080.
- One config file for all keys: config/yogdaan.env (gitignored; template config/yogdaan.env.example).

## How Claude works on the laptop
- The chat must be linked to the laptop (Claude desktop app). Files live in D:\ngo-platform.
- Code is edited in the cloud copy, packed with sync (tar) and copied to D:\ngo-platform\.sync, then applied.
- PC commands: write D:\ngo-platform\.agent\cmd-NNN-name.ps1; the runner executes them in order and writes
  out-cmd-NNN-name.log (moved to .agent\done after). Read logs with grep/tail.
- Build: scripts\build-backend.cmd -pl <modules> (stop those services first). Start: scripts\start-local.ps1
  (never pipe/redirect its output). App: scripts\run-mobile.ps1 -Mode profile [-Analyze]. Emulator 1080x2400,
  UI helper scripts\ui.ps1 (Tap, TypeText, Labels, Shot...). psql needs $env:PGPASSWORD='dev-only-password' and -w.
- PowerShell 5.1 misreads non-ASCII (e.g. the rupee sign) in .ps1 command files: avoid it there.
- Push: git bundle (<last pushed>..main) -> D:\ngo-platform\.sync -> fetched into D:\ngo-git\ngo-platform -> push.
  Last pushed commit: db04e26 (CSR 3).

## Test data (DEV ONLY)
NGO "Asha Seva" (id 0cf945f6-1183-42f5-aeb1-20bab9be302b), Professional trial; tests switch to ENTERPRISE via
PUT /api/v1/ngos/{id}/subscription/trial-plan and back (entitlement cache 30 s).
Users: asha.admin@ngo.test / DevNgo#2026 (admin), ravi (2nd admin), sunil.staff@ngo.test / DevTest#2026, priya
(staff), donors meera.donor@ngo.test / DevDonor#2026, kiran, anil. Seeds: scripts/seed-*.ps1. E2E: scripts/test-*.ps1
(profile, phone, notifications, campaigns, admin-limit, donations, expenses, documents, receipts, csr, uc,
proposals; load: test-load-receipts with start-local -ReceiptCopies 3).

## Status (done)
Profile + 18+ gate, verified mobile (OTP), campaigns + approval + share page, online and offline donations,
receipts R1/R2 + parallel workers (3 copies tested: 75 receipts in 11.5 s), campaign accounts, document vault +
meetings, Yogdaan rename + master config, CSR 1 compliance pack (expiring public link), CSR 2 utilisation
certificate (NGO signs, CA certifies via link with UDIN check, PDF in vault), CSR 3 funding proposals (from a
campaign, frozen PDF when sent, follow-ups, funded -> UC pre-filled, pipeline).

## Open issues
- Intermittent JVM crash (access violation, offset ...6d12, no hs_err) in receipt and campaign services, around PDF
  rendering. Laptop memory is near its limit (1.6 GB RAM free, commit 34.7/37.3 GB): a likely factor.
  Windows crash dumps need the owner's approval.
- Event relay: one service down holds up later events for all (head-of-line blocking).
- test-receipts archives the owner's custom receipt design.
- S3 region hard-coded (US_EAST_1) in commons S3ObjectStorage: fix before Oracle Cloud.
- UC page refreshes only on pull-down when the CA certifies.

## Next (suggested order)
1. Trim service memory (stacks, metaspace, code cache) and re-check the crash.
2. Payment gateway (real) or Google/Facebook/Apple sign-in (owner creates the OAuth apps).
3. Account deletion end to end; unsubscribe links; donor PAN only when 80G is wanted; referral links.
4. Form 10BD/10BE export, advanced receipt designer, donor receipts in their vault.
5. Operations (volunteers, beneficiaries), helpdesk, reporting; push notifications.
6. Oracle Cloud free tier (A1 2 OCPU/12 GB, Mumbai/Hyderabad, Caddy, one bucket), iOS (needs a Mac), Play release.

## Owner decisions on record
CSR = Enterprise for now (flags CSR_COMPLIANCE_PACK, CSR_UTILISATION_CERT, CSR_PROPOSALS, re-decidable). CSR papers
are linked from the NGO's vault, not copied. Receipts carry "Generated with Yogdaan". No under-18 accounts.
Gender question pending (recommendation: optional, "Prefer not to say").
