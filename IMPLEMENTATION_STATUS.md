# Implementation status

_Last updated: 2026-10-29 (campaigns session)._ Earlier header: _2026-10-28 (session 1)._ Nothing here is production-ready; see "Verified" column._

## Environment (developer PC, Windows 11)
| Item | State |
|---|---|
| Git, Temurin JDK 21.0.12, Maven 3.9.9, Flutter 3.47.8 / Dart 3.13.7 | Installed in D:\tools — verified |
| Android SDK 36, build-tools 36/35/28.0.3, emulator 37.2 + AVD `ngo_pixel` (Pixel 7, API 35, WHPX) | Installed — verified |
| Podman (WSL machine) | Pre-existing, running; infra pod images were still downloading |
| **Constraint** | PC hit 96% RAM / 90% CPU with emulator + Podman + builds. Running all 12 JVM services + emulator together is not feasible on this machine; use `start-backend.cmd -Only identity-service,ngo-registry-service,subscription-service,api-gateway` or more RAM. |

## Backend modules
| Module | Code | Compiled on PC | Tests | Notes |
|---|---|---|---|---|
| platform-commons | done (security, outbox/inbox, audit, idempotency, storage, errors) | yes (build 1) | none yet | |
| api-gateway | routing, JWT, rate limit, body limit, headers, /internal block | yes | none | |
| identity-service | users, memberships, roles/permissions, invitations, seat locking, admin safeguards, step-up | yes (before query refactor) | none | |
| ngo-registry-service | wizard, documents (S3), verification workflow, suspension, change requests, public search | yes (before refactor) | none | |
| subscription-service | catalog, entitlements, trial, quotes/checkout, proration, blocks, downgrade impact, lifecycle, reminders, invoices PDF, mock billing | NOT YET | none | |
| donor-service | donors, dedupe/merge, import/export, segments, follows, privacy | NOT YET | none | |
| donation-payment-service | online checkout with test provider, webhooks, ledger, team/80G/campaign rules (2026-10-29); refunds, reconciliation, offline/corrections drafted only | yes (2026-10-29) | 5 unit; e2e test-donations 34/34 | Razorpay adapter not built |
| receipt-service | receipts after every paid donation (online + offline), numbering PREFIX/<FY>/000001 per NGO and financial year (gap-free), standard A5 PDF (logo, 12A, 80G block, amount in words, up to 3 signatories with signature images, QR verification), e-mail + in-app delivery, donor/guest download, NGO register + search + summary, reissue/void with history, vault entries; Basic designer (own background picture + fields) (2026-11-01) | yes (2026-11-01) | unit 19+; e2e test-receipts | Advanced designer (Enterprise: conditions, several templates, approval UI) not in the app yet; Form 10BD export later |
| campaign-project-service | campaigns: approval workflow, photos, share page, public discovery, test donations (2026-10-29); campaign accounts: budget, expenses + bills + approval, vendors, staff cash ledger, account sheet PDF/Excel (2026-11-01); CSR utilisation certificates with CA certification and CSR funding proposals (Enterprise, 2026-11-02); projects drafted only | yes (2026-10-29) | 15 unit; e2e test-campaigns 58/58 | projects not reviewed/tested yet |
| ngo-operations-service | **not started** (volunteers, beneficiaries, expenses/finance) | | | |
| helpdesk-service | **not started** | | | |
| notification-service | done: templates en/hi/mr (10 events x e-mail + in-app), event consumer, in-app inbox, e-mail via SMTP (Mailpit), retries with backoff + dead letter + replay, preferences/opt-outs, history, admin template edit/preview | yes (2026-10-29) | 58 unit tests pass; API e2e `scripts/test-notifications.ps1` | push/SMS/WhatsApp adapters not built (interface ready); receipt e-mails wait for receipt-service |
| document-service | CSR compliance pack (Enterprise, 2026-11-02: vault papers shared through one expiring link, frozen files, view log); document vault (one shared private bucket, `ngos/<id>/` prefixes, per-NGO quota, signed 5-minute links, recycle bin, activity log, files of other modules registered by events + backfill) and meetings (AGM/EGM/board/committee/staff, attendance, minutes, numbered resolutions, action items, minutes PDF) (2026-11-01) | yes (2026-11-01) | 16 unit; e2e test-documents 57/57 | expiry reminders, versions, CA read-only access = phase 3 |
| reporting-service | **not started** (dashboard projections) | | | |

## Mobile app
Done: design system, l10n (en/hi/mr, ~245 keys, hi/mr need native review), onboarding (APP-001..009, 012),
auth (PKCE via Keycloak), NGO start/invitations, 6-step registration wizard with uploads, NGO home, subscription
(overview, plans, quote, mock payment, billing/invoice PDF), staff (list, capacity, invite, activate), NGO tabs
(donors/campaigns/reports lists), donor discover/NGO profile/donations/following/profile, admin verification
queue/decision, settings. Unit tests: validators, formatting, error mapping (~25 cases).
Not done: donation checkout UI, receipts UI, campaign/project editors, operations, helpdesk, notifications, designer.

First on-device build was in progress at end of session (run-mobile.cmd).

## Tests executed
- Backend: `mvn verify` once (commons, gateway, identity, registry compiled; no tests yet).
- Mobile: none executed yet.
Target per spec: 1,000+ meaningful tests — current: ~25 written, 0 executed.

### Session 2026-10-29 (night): notification-service built and tested
- New service (port 8091, DB `notification`, Flyway V1 schema + V2 60 seeded templates; generator `tools/gen_templates.py`).
- Consumes: ngo.verification.approved/rejected/info-requested, ngo.account.suspended, identity.staff.invited/accepted,
  subscription.subscription.expiring (D15/D7/D1/EXPIRED/GRACE_ENDING/SUSPENDED), subscription.payment.failed.
  Recipients/names fetched from identity (`/internal/v1/tenants/{id}/admins`, `/internal/v1/users/lookup` - new) and registry.
- API: `/api/v1/notifications` (inbox, unread-count, read, read-all, history, preferences),
  `/api/v1/admin/notification-templates` (list/edit with placeholder + version checks/preview),
  `/api/v1/notifications/admin/deliveries` (dead letters + retry), `/internal/v1/notifications` (service send).
- App bug found and fixed: "Switch to NGO app"/"Switch to donor app" changed the saved mode but did not navigate
  (router now moves /donor/* <-> /ngo/* to the selected app's home).
- Registry events now carry `displayName` (+ approval message). start-local starts notification-service by default.
- App: bell with unread badge (NGO home, start page), inbox, preferences (ACCOUNT locked), "Messages sent to you".
  Verified on the emulator (profile build): bell on the NGO home, inbox list, preferences with ACCOUNT locked.
- Tests: 58 unit tests (renderer, delivery state machine/backoff, planner rules, event mapping) pass on the PC.
  API e2e `scripts/test-notifications.ps1` (re-runnable, resets its own test data): 26/26 passed. Verified live: invitation e-mail in Mailpit, in-app inbox,
  "joined" notice to the admin, preference rules, admin template rules, SMTP outage -> FAILED + retry -> delivered.

### Session 2026-10-29 (morning): profile completion + adults-only gate (plan item A2) - built and tested
- identity: V2 migration (birth_date, gender, avatar_kind/preset, photo_version, adult_confirmed_at,
  profile_completed_at, refused_subjects); `PUT /api/v1/me/profile` (birth date fixed once saved -> 409
  BIRTH_DATE_LOCKED), `PUT /api/v1/me/avatar` (a01..a16), `POST|GET|DELETE /api/v1/me/photo`,
  `GET /api/v1/users/{id}/photo` (self, same-NGO members, platform support only; others 404),
  `/internal/v1/users/{id}/profile-status`. Team list carries avatar data.
- Photo pipeline: magic-byte check (JPEG/PNG <= 5 MB), dimension check before decoding (no decompression bombs),
  centre square crop, 512 + 128 px JPEG re-encode (all EXIF/GPS removed), versioned keys, old versions deleted.
- Adults-only gate (commons `ProfileGate` on every servlet service; positive answers cached 10 min, negative never):
  403 PROFILE_INCOMPLETE until the profile is saved; open: /me, /me/profile, /me/photo, /me/avatar,
  /me/deletion-request, /public/**; platform staff exempt.
- Under 18: 403 UNDERAGE; new account deleted in Keycloak (admin client `ngo-identity-admin`, manage-users only;
  `scripts/fix-identity-admin-client.ps1` for the running realm) and locally, subject blocked for 1 day so an old
  token cannot recreate it (403 ACCOUNT_REMOVED), audit row without personal data. Accounts already in an NGO are
  disabled instead (support follows up). Existing e-mail under a new sign-in subject -> 409 ACCOUNT_CONFLICT.
- App: "Tell us about yourself" (forced after sign-in until saved), "My profile" (Settings / donor Profile), picture
  menu (camera, gallery via image_picker, 16 built-in avatars, remove), date picker, gender, 18+ confirmation,
  under-18 warning dialog then local sign-out; avatars in Settings, donor profile, team list.
- Tests: commons ProfileGateTest 8, identity AgesTest 10 + PhotoProcessingTest 8 + ProfileRulesTest 9 (all pass);
  API e2e `scripts/test-profile.ps1` 29/29; notifications e2e still 26/26 with the gate on.
  UI verified on the emulator: Sunil forced to the profile screen, avatar chosen, birth date picked, lands on home;
  Asha's My profile shows the birth date locked.
- Pending small polish (built in the next app build): smaller camera badge on the picture, no "0/10" counter.

### Session 2026-10-29 (afternoon): standard dataset + verified mobile (plan items A7, A8) - built and tested
- `scripts/seed-dataset.ps1` (idempotent; verified twice): Asha (primary admin), Ravi (secondary admin), Sunil (staff),
  Priya (finance) - all with verified phones; donors Meera (verified phone), Kiran, Anil (no phone); languages en/hi/mr.
  Documented in docs/testing/TEST_DATASET.md; CLAUDE.md rule added. E2E scripts now run on this dataset.
- identity V3: phone stored only after SMS OTP (HMAC-hashed codes, 5 min, 5 tries, 60 s resend, 5/day per account
  and per number), one verified number per account (unique index), old unverified numbers wiped.
  `POST /api/v1/me/phone/otp`, `POST /api/v1/me/phone/verify`, `DELETE /api/v1/me/phone` (refused for team members).
  SmsSender adapter + dev adapter delivering to Mailpit; DLT template text in SmsSender.OTP_TEMPLATE.
- Gate extended: NGO team members (any active membership) need a verified phone (MeService.isUsable; MeView
  phoneRequired/phoneVerified). Remote gate cache shortened to 1 minute so a new role applies quickly.
- Contact privacy: phone visible to self, to active members of the same NGO (team list, with call button) and platform
  support; donors never listed; every team-contacts view audited (TEAM_CONTACTS_VIEWED, no numbers in the log).
- App: phone row with "Verified" badge and a two-step verify sheet (number -> code, resend countdown, SMS autofill);
  profile screen explains when an NGO role requires a phone; team list shows numbers + call.
  App compiled and installed (profile build). Team list shows phones + Call to same-NGO staff (verified on emulator). PhoneVerifySheet hardened: code field autofocus, digits only, auto-verify at 6 digits, Verify disabled until then (empty-code bug found by UI check).
- Tests: identity unit tests 39 (incl. PhoneVerificationRulesTest 9); e2e test-phone 23/23, test-profile 29/29,
  test-notifications 26/26 (Priya's invitation now verified to arrive in Marathi).
- Laptop: `scripts/keep-awake.ps1` (no power-setting change; stops after 12 h; `stop-keep-awake.ps1`).

### Session 2026-10-29 (evening): campaigns module (owner switched priority from plan item A to campaigns)
Owner decisions: campaigns only this round (projects next); any team member drafts and sends for approval, only NGO
admins approve/publish, request changes (with reason), edit a live campaign, close and archive; staff/finance/volunteer
coordinator roles now carry CAMPAIGN_MANAGE (VOLUNTEER does not); test donations until online payments exist;
dataset unchanged (2 admins / 2 staff / 3 donors).
- campaign-project-service V2: submitted/rejected/updated by + timestamps, rejection reason, NGO name/city snapshot at
  approval, image version, cause check; campaign_donations gains is_test/donor_label/recorded_by/created_at.
- Workflow (CampaignRules, 11 unit tests): DRAFT -submit-> PENDING_APPROVAL -approve(version)-> PUBLISHED -close->
  CLOSED -archive-> ARCHIVED; reject -> DRAFT + reason; withdraw; admin may publish own draft. Approval needs active
  subscription, verified NGO (registry donationEligible) and plan live-campaign limit. Goal Rs 1,000-10 crore,
  end date required, max 1 year, live edits keep start date and goal >= raised. Auto-close after end date (IST).
- Cover photo: 16:9 crop, 1200 + 480 px JPEG re-encode (metadata/GPS stripped), team endpoint for drafts, public
  endpoint only when live/closed (CoverImage, 4 tests).
- API: list (excludes archived) / summary (counts + live limit) / create / get / update / submit / withdraw / approve /
  reject / close / archive / image / donations / test-donations; public list (cause, text search, NGO) + detail without
  team data (phase LIVE/UPCOMING/ENDED).
- Notifications: CAMPAIGN_SUBMITTED (to admins except submitter), CAMPAIGN_APPROVED and CAMPAIGN_REJECTED (to the
  requester; none when an admin publishes own draft); en/hi/mr templates (notification V3); 4 new listener tests.
- App: Campaigns tab (filters, pending-approval banner for admins, live count vs plan), detail with workflow actions by
  role, create/edit form (cover picker, cause, goal, dates), test-donation sheet, donations list; donor home "Live
  campaigns" strip, all-campaigns page with search/cause, campaign page (Donate shows "coming soon"), NGO page lists
  its campaigns. 88 new strings en/hi/mr (hi/mr need native review). flutter analyze: no errors/warnings.
- Tests: campaign unit 15, notification unit 62; e2e `scripts/test-campaigns.ps1` 58/58 with Asha, Ravi, Sunil,
  Priya, Meera. Sample data `scripts/seed-campaigns.ps1` (2 live, 1 awaiting approval, 1 draft).
- Emulator check (2026-10-29 evening): Sunil list/pending detail/new form, Asha approval banner + Approve/Request
  changes, live detail with test donations, donor home "Live campaigns" strip and campaign page - all as designed.
  Fixed on the way: staff quick actions on NGO home were greyed out because staff cannot read the subscription
  (now only blocked when the subscription is known to be lapsed; server enforces anyway); closed campaigns show the
  closing date. Emulator notes: after `adb root` the port reverse (8080/8180) must be re-added; Chrome must stay
  enabled (sign-in page); unused Google apps (Messages, Dialer, YouTube, Photos, Gmail) disabled in the emulator
  because they kept raising "not responding" dialogs.
- Not yet: share link (CAMP-010), analytics (CAMP-009), saved campaigns (DAPP-023), campaign updates to followers,
  real donations, cause labels are English-only in all languages (pre-existing causeLabel).

### Session 2026-10-29 (late evening): owner review of campaigns - photos, share link, donate rule, admin limit
- Who sees Donate (owner rule): never in the NGO app; in the donor app an active member of the campaign's NGO sees
  "Your organization's campaign" + Share instead of Donate (campaign page and NGO page). Members can still donate to
  other NGOs. ("Add test donation" in the NGO app is a development-only stand-in, off unless CAMPAIGN_TEST_DONATIONS.)
- Photo gallery: up to 5 photos per campaign, first = cover (campaign V3 `campaign_photos`, existing covers migrated).
  API: POST /photos, DELETE /photos/{id}, PUT /photos/{id}/cover, GET team/public photo (16:9, 1200/480 px, metadata
  stripped, public only when live/closed). App: photo strip in the form (multi-pick, make cover, remove; applied on
  save), swipeable gallery with dots on team and donor pages; consent/dignity hint for photos of people and children.
- Share link (CAMP-010): public page GET /c/{id} (campaign-service, via gateway, no login) with Open Graph/Twitter
  tags (photo, title, summary, NGO) so Facebook/LinkedIn/WhatsApp/X show a rich preview; all text HTML-escaped; 404
  for drafts. Views carry `shareUrl` (base `ngo.public-web-url` / PUBLIC_WEB_URL - set to the product domain in
  production). App: "Share campaign" (system share sheet: WhatsApp, Facebook, LinkedIn, Instagram DM/Stories, ...)
  + "Copy link". Instagram posts cannot carry clickable links - use Stories link sticker / bio.
- Admin limit: server already refused a 3rd admin (ADMIN_LIMIT_REACHED for invite, promote, reactivate); the app's
  invite sheet still offered "Secondary admin". Now hidden when 2 admins (active + pending) exist, with an
  explanation; team card shows "2 of 2 administrators". New e2e `scripts/test-admin-limit.ps1`.
- Tests: campaign unit 19 (SharePageControllerTest 4 new), e2e test-campaigns 76/76 (photos, share page, XSS).
- Later: app links so /c/{id} opens the app (needs the domain + assetlinks/apple-app-site-association), referral tag
  on shared links, Hindi/Marathi share page.

### Session 2026-10-29 (night): online donations, part 1 (owner decision: money goes straight to each NGO's account)
- donation-payment-service draft aligned and compiled for the first time: V2 (donor_id optional until donor-service,
  80G address, NGO/campaign display snapshots, 80G checks); donor-service call behind `ngo.donors.resolve-enabled`.
- Checkout rules: NGO verified + subscription operational + payments ACTIVE; campaign must be live and within dates
  (campaign internal API now returns dates); NGO's own team members refused (TEAM_MEMBER_DONATION, owner rule);
  amount Rs 10..10 lakh; idempotency key per checkout; 80G needs PAN + address and a verified, unexpired 80G
  registration. Paid -> ledger entry + donation.donation.paid -> campaign raised amount + notifications.
- Test payment provider (MOCK) stands in for Razorpay: app shows a clearly-labelled "Test payment" screen
  (pay / fail / cancel); signed webhooks, duplicate-webhook dedupe, late events cannot undo PAID.
- NGO side: More > Online donations (status, "Turn on test payments"; "Connect Razorpay" placeholder until the
  platform's Razorpay partner account exists). Seed: `scripts/seed-donations.ps1` (80G test number + test payments).
- Notifications (notification V4): DONATION_THANKS to the donor (e-mail + in-app), DONATION_RECEIVED to NGO admins
  (in-app, no donor name). Event routes: donation.paid -> campaign, notification only (HTTP relay stops at the first
  unreachable subscriber; add receipt/donor when those services ship).
- App: Donate sheet (amount chips, 80G switch with PAN/address prefilled from the donor's last 80G gift), test payment
  screen, result (thank you / failed, try again / pending, check again), My donations with total given.
- Tests: donation unit 5, notification unit 64; e2e `test-donations.ps1` 34/34, test-campaigns 77/77,
  test-notifications 26/26 (template count check now derived).
- Found: Asha's saved language had become Marathi (cause not identified; seed-dataset restores it). The PC
  command runner hung when a test restarted Mailpit with Start-Process; the test now starts it detached (WMI).
- Next: 80G/standard receipts (receipt-service), offline donations (cash/cheque) in the NGO app, NGO donations list,
  Razorpay adapter (needs the platform's Razorpay partner account), guest donations from the share page.

### Session 2026-11-01: campaign accounts - expenses, bills, vendors, budget, staff cash, account sheet
Owner request: "track expenses per campaign so that at the end of the campaign they can generate the account sheet,
instead of registers and receipts kept by different staff members". Owner decisions: every expense is approved by an
administrator; staff advances and reimbursements are tracked; budget vs actual per category; spending is NOT shown to
donors.
- campaign-project-service V4: vendors (NGO-wide, unique name, GSTIN/PAN/phone validated, CSV import/export +
  template), campaign_budget_lines, campaign_expenses (SUBMITTED -> APPROVED with voucher EXP/<FY>/nnnn |
  REJECTED -> edit -> SUBMITTED; APPROVED -> VOID with reason), expense_attachments (bill photos re-encoded without
  EXIF/GPS, or PDF; max 5), staff_money_entries (immutable ledger: ADVANCE, EXPENSE, RETURN, REIMBURSEMENT,
  EXPENSE_VOID). DB triggers keep approved expenses and the ledger immutable.
- Rules (AccountRules): expenses only on live/closed campaigns; bill or "no bill" reason required; date not in the
  future and at most 90 days before the campaign start; staff record only money they paid themselves; admins only
  approve, never their own expense unless they are the only admin (marked self-approved); returns/reimbursements
  cannot exceed the balance (advisory lock per staff member); budget edited by the team before approval, by admins
  after; archiving blocked while expenses wait for approval (archive = books closed, account sheet FINAL).
- Account sheet: JSON for the app, PDF (A4 landscape: summary, budget vs actual, vouchers, pending, voided, staff
  cash, signature lines; Arial on Windows for the rupee sign, Helvetica + "Rs." fallback) and Excel (fastexcel:
  Summary, Expenses, Pending and void, Staff cash).
- Notifications (V5, en/hi/mr): EXPENSE_SUBMITTED to admins (not the submitter), EXPENSE_APPROVED (in-app) and
  EXPENSE_REJECTED (in-app + e-mail) to the submitter. Gateway routes /expenses, /vendors, /staff-money.
- Roles: CAMPAIGN_MANAGER and VOLUNTEER_COORDINATOR also get EXPENSE_VIEW + EXPENSE_RECORD.
- App: "Campaign money" card on the campaign page (received / spent / balance, budget used, pending), expenses list
  with filters, add/edit expense (camera, gallery or PDF bills; vendor picker with quick add; who paid), expense
  detail (bills full screen, approve / send back / cancel), budget editor with spend bars, account sheet with
  PDF / Excel download (open or share), vendors (search, add, edit, CSV import/template/export), staff advances
  (admins: everyone's position, give advance, cash returned, reimburse; staff: My advances), home card
  "Expenses to approve" for admins. ~175 new strings (hi/mr drafts).
- Seeds/tests: `seed-expenses.ps1` (School books: budget, 3 vendors, approved bills, one pending, one sent back,
  advance to Sunil); `test-expenses.ps1` e2e; unit tests AccountRulesTest (10), AccountSheetExportTest (2),
  notification EventsListenerTest (+4). E2E cleanup moved to `scripts/sql/cleanup-e2e-campaigns.sql`.
- Found: the PC's helper hung on a psql password prompt (test script missed PGPASSWORD); psql now runs with -w.
- Tests: campaign unit 31, notification unit 68; e2e test-expenses 66/66, test-campaigns 77/77, test-donations 34/34,
  test-notifications 26/26; app analyze 0 errors (infos only); verified on the emulator as Asha (approve a bill,
  account sheet, budget, add expense, vendors, staff advances) - screenshots docs/screenshots/accounts-*.
- Next for accounts: projects get the same expense module; NGO-wide (non-campaign) expenses and income register for the
  annual accounts; Form 10B / utilisation certificate export for the CA; expense receipts by e-mail to vendors.

### Session 2026-11-01 (afternoon): document vault and meetings (phase 1 of the agreed document plan)
Owner request: "keep every document on object storage, isolated per NGO, retrieved only after login; admins see
everything staff upload, receipts, legal documents; staff can upload; searchable" + "how will AGM and other minutes
of meeting be tracked". Owner decisions: staff see their own uploads + documents shared with the team, admins see all;
no search inside files (category, name, type, dates, tags, uploader only); quota 2 / 10 / 50 / 100 GB for
Community / Growth / Professional / Enterprise; ONE object store for all NGOs (built for 2 lakh NGOs).
- Storage: one private bucket `ngo-files` for every service (S3_BUCKET in commons-defaults; local files moved from the
  old per-service folders). Keys `ngos/<ngoId>/<module>/...`; isolation is enforced by the services (membership +
  permission on every request), never by public URLs. Downloads only through the API or a signed 5-minute link
  (HMAC, re-checks membership on use, `Content-Disposition: attachment`, CSP sandbox, nosniff).
- New document-service (port 8093, DB `document`): documents (categories Legal & registration, Tax & compliance,
  Finance & bills, Campaigns, Projects, Agreements, Meetings, HR, Photos & reports, Receipts, Other), tags, document
  date, expiry date, visibility TEAM or RESTRICTED (uploader + admins; default for Legal, HR, Meetings, Agreements),
  module files carry a required permission (bills need EXPENSE_VIEW). Upload checks file content (PDF, JPEG/PNG
  re-encoded without EXIF/GPS, Word/Excel/PowerPoint), 25 MB max, quota from entitlement STORAGE_MB_MAX
  (subscription V2). Recycle bin 30 days then purge job; Finance / Tax / Receipts kept 8 years even when deleted.
  Append-only activity log (uploaded, viewed, link, downloaded, updated, deleted, restored), admins only.
- Other modules: registry documents and expense bills are registered in the vault by events
  (`document.file.registered/removed`) and a one-time backfill (internal endpoints in registry + campaign). The file
  stays in its module and is managed there (MANAGED_ELSEWHERE); the vault lists, searches and opens it.
- Meetings: AGM, EGM, board, committee, staff, other; date/time, in person / online / hybrid, agenda, quorum,
  invitees (team + guests with designation); board/AGM/EGM visible to invitees + admins by default. Minutes after the
  meeting started: attendance, chairperson, proceedings, resolutions (proposer, seconder, votes, result), action
  items with owner and due date. Approval locks everything (DB trigger), numbers resolutions RES/<FY>/nnn and files a
  minutes PDF (attendance, quorum, agenda, proceedings, resolutions, actions, signature lines) in the vault (Meetings).
  Action items can be ticked off by the owner or an admin, also after approval. AGM tracker per financial year
  (due by 30 September). Notifications: invitees on scheduling, owner on a new action item (in-app, en/hi/mr).
- Permissions: DOCUMENT_VIEW / DOCUMENT_UPLOAD / GOVERNANCE_VIEW for staff roles; GOVERNANCE_MANAGE admins only.
  Gateway routes /library, /meetings, /action-items, /api/v1/public/files.
- App: Documents (search, storage bar, category chips with counts, type / date / mine / tag / sort filters, upload
  from camera, gallery or files, detail with preview / open / share / edit / delete, expiry warnings, link to the
  expense or meeting, admin activity log, recycle bin); Meetings & minutes (AGM card, my action items, minutes to
  approve, upcoming / past, schedule / edit, minutes editor with attendance, resolutions, action items, attach
  documents, approve, minutes PDF, cancel); My action items; home card + Documents quick action; ~193 strings.
- Seeds/tests: `seed-documents.ps1` (5 documents incl. one expiring soon, approved AGM 2026, approved October board
  meeting with action items, November board meeting with draft minutes, upcoming staff + committee meetings);
  `test-documents.ps1` e2e 57/57; unit DocumentRulesTest, DocFilesTest, LinkSignerTest, MeetingRulesTest (16).
  Found: E2E campaign cleanup left bills in the vault (rows deleted without events) - document cleanup now removes
  bills of E2E campaigns; download filename header was Q-encoded - now ASCII + RFC 5987 filename*.
- Regression: test-expenses 66/66, test-campaigns 77/77, test-donations 34/34, test-notifications 26/26.
- Next (agreed phases): phase 2 donor receipts (PDF + QR) in the vault for donors; phase 3 expiry reminders,
  document versions, CA read-only access.

### Session 2026-11-02 (afternoon): memory - thread leak fixed, emulator guard, JVM native caps
- Measured first (cmd-356): donation-payment-service had 1,908 threads (others ~50); the emulator (qemu) held
  16.5 GB of commit after ~22 h of uptime; all 10 services together ~4 GB. Laptop commit 36.9/40.2 GB.
- Thread leak (platform-commons InternalClients): every forBaseUrl() call built a new java.net.http.HttpClient,
  each with its own SelectorManager thread; HttpOutboxRelay called it once per relayed event (1,856 leaked
  selector threads). Now one HttpClient per service and one cached RestClient per base URL. Unit test
  InternalClientsTest (same instance; 400 calls start no selector thread). After the fix + donations E2E:
  45 threads, 2 selector threads per service.
- Emulator: a fresh boot commits 4.3 GB (vs 16.5 GB after a day): restarting freed 12 GB (commit 36.4 -> 24.2 GB).
  run-mobile.ps1 now restarts the emulator when its commit passes 8 GB.
- JVM flags (start-local.ps1): CompressedClassSpaceSize=64m, ReservedCodeCacheSize=48m, MaxDirectMemorySize=64m,
  Min/MaxHeapFreeRatio=10/30. Per service ~290-315 MB private after load (was 350-370 MB).
- Verified: build + all unit tests OK; E2E donations 34/34, UC 29/29, proposals 35/35; load test 75 receipts on
  3 receipt copies in 15.5 s, 5/5. No JVM crash, no hs_err, no service down. The crash stays "open, not seen
  since": memory starvation (commit near the limit) is the likely cause; re-check if it ever returns.
- No UI change, so no emulator walkthrough.

### Session 2026-11-02: receipts Phase 1 - parallel workers, fair queue, load test
- Worker: claims batches of 10 with FOR UPDATE SKIP LOCKED (several copies, all in Eureka, share the queue without
  collisions); order = new receipts before retries, round-robin across NGOs (oldest of every NGO first); a round
  stops after 60 s so retries and e-mail re-sends get their turn. The copy (host:port) is written on GENERATED.
- Caches: published design version (definition + background) and logo by storage key (both immutable).
- Designer previews in their own lane: at most 2 at a time, 20 per NGO per minute (429/503 with a clear message).
- Metrics: receipts.generate timer (ok/failed), receipts.preview timer, receipts.queue.pending gauge (prometheus).
- start-local.ps1 -ReceiptCopies 3 (extra copies on 8097, 8107), -ReceiptJit c1|full|int, -ReceiptDiag
  (PrintCompilation) for the crash investigation. scripts/test-load-receipts.ps1 (-Count 75 -Threads 25).
- Load test, 75 cash donations at once (standard design): 1 copy - all issued in 22 s, average wait 8.3 s, longest
  11.7 s; 3 copies - 11.5 s, average 1.7 s, longest 3.9 s, split 23/13/39; one receipt per donation, numbers
  consecutive, no errors; test-receipts 68/68 with 3 copies running.
- Crash: the previous build (one-at-a-time lock, old worker) crashed within a minute of the first burst; Windows
  reports the same faulting address ending ...6d12 every time (1 Nov 23:08 and 2 Nov 09:25: same instruction in
  JIT-generated code, no hs_err), so the lock was not the fix and the JVM/JIT is the prime suspect. The new build did
  not crash in either burst. Root cause still open: reproduce with the old jar under -ReceiptJit full / int.
- Event relay note: HttpOutboxRelay retries forever (nothing lost) but delivers in order, so one unreachable
  subscriber holds up later events for every subscriber until it is back.

### Session 2026-11-02: CSR 3 - funding proposals (campaign-project-service, Enterprise)
- campaign V6: csr_proposals (+ proposal_counters, numbering PR/<FY>/nnn per NGO), proposal_events (append-only log),
  guard trigger (sent text and PDF frozen, closed proposals immutable, never deleted); utilisation_certificates gets
  proposal_id. document V6: vault source CSR_PROPOSAL.
- Flow: a draft starts from one campaign (title, story as "the need", Schedule VII area guessed from the cause,
  12 months, location, contact from the NGO profile; ask = what the campaign still needs unless given). Sections:
  need, objectives, activities, outcomes, monitoring (standard text if empty: quarterly reports + CA-certified UC).
  Compliance pack: only our own public pack links can be printed. Sending needs need/objectives/activities/outcomes
  and an unexpired pack link; it stores the PDF as sent (SHA-256, vault category CSR, linked to the campaign) and sets
  the follow-up date. Notes (calls, meetings) with a new follow-up date while open. Outcome: FUNDED (amount, date,
  funder reference), DECLINED (reason, only after sending), WITHDRAWN. Copy = new draft with the same text. A funded
  proposal opens the UC form pre-filled and the UC is linked back (only funded proposals of the same campaign).
- Pipeline summary on the list: drafts, open (money asked), funded (money won), success % of decided, follow-ups due.
- ProposalPdf (A4): letterhead, key facts box, five sections, budget by head with spent so far (+ "other expenses"
  so the total matches the progress), progress so far, organisation details, pack link, contact; DRAFT watermark.
  Amounts in Indian grouping (2,50,000) in proposal and UC PDFs (UcRules.group; the JDK's en-IN groups in thousands).
- Writes need CSR_PROPOSALS; reading, notes and recording the outcome stay possible after a downgrade. Admins only.
- App: CSR documents > Funding proposals: list with pipeline tiles, new sheet (campaign, company, amount), proposal
  page (stage card, sections, progress with budget, log, linked UCs), editor page (all fields, Schedule VII dropdown,
  shared-pack picker), mark-sent sheet (then share the PDF), note sheet, funded sheet, decline/withdraw menu, copy.
  ~100 strings en/hi/mr.
- Tests: ProposalRulesTest (6), ProposalPdfTest (2); test-proposals.ps1 35/35; test-uc 29, test-csr 39.
  Emulator walkthrough: PR/2026-27/009 to Sahyadri Industries CSR Foundation drafted, completed, pack attached, sent,
  note added, funded Rs 2,00,000 (ref SCF/2026/14), UC form pre-filled (demo data, left in place).

### Session 2026-11-02: CSR 2 - utilisation certificate (campaign-project-service, Enterprise)
- campaign V5: utilisation_certificates (+ uc_counters, numbering UC/<FY>/nnn per NGO) with guard trigger: no delete,
  figures frozen into JSONB at signing, certified rows can only be cancelled, cancelled rows immutable.
  document V5: vault source UTILISATION_CERT.
- Flow: DRAFT (live figures from the campaign's APPROVED expenses in the period, grouped by budget head, with
  unspent balance and "from own funds" when spending exceeds the grant) -> NGO signs (name + designation; figures
  frozen; CA link valid 14 days; API status AWAITING_CA / CA_LINK_EXPIRED) -> the CA opens the public page
  /api/v1/public/uc/{code} (no login; own CSP incl. form-action 'self'), reviews statement + expenses + draft PDF and
  certifies with name, ICAI membership no. (6 digits), UDIN (18 chars, must contain the membership no., this or last
  year), optional firm/FRN, place, declaration -> final PDF stored at ngos/<id>/csr/uc/<id>.pdf with SHA-256 and
  filed in the vault (category CSR, linked to the campaign, suggested for CSR packs). Cancel keeps the record
  (vault entry renamed "Cancelled: ..."); new CA link invalidates the old one. Writes need CSR_UTILISATION_CERT;
  reading and cancelling still work after a downgrade.
- UcPdf (A4): letterhead, grant details, statement by budget head, certificate wording, NGO + CA sign-off with the
  CA declaration, annexure of every expense (bill yes/no), "Generated with Yogdaan" footer; drafts carry
  "DRAFT - NOT CERTIFIED" (watermark + footer).
- App: CSR documents > Utilisation certificates, and a button on the campaign account sheet (admins). List, form
  sheet (campaign, funder, reference, CSR-1 no., grant, received date, period), certificate page (stage card,
  figures, heads, expenses, PDF, sign dialog, CA link share/copy/renew, cancel with reason). 66 strings en/hi/mr.
- Tests: UcRulesTest (5), UcPdfTest (2); test-uc.ps1 29/29; regressions test-csr 39, test-documents 57,
  test-expenses 66, test-campaigns 77. Emulator walkthrough: UC/2026-27/004 prepared, signed and certified by a
  test CA through the emulator browser (demo data, Sahyadri Industries CSR Foundation).
- Fixed during the walkthrough: CA declaration overlapped the signature blocks in the PDF. UC/2026-27/004 was
  certified before that fix and keeps its stored PDF (certified files are never re-rendered).
- Open: the certificate page refreshes only on pull-down when the CA certifies while the app is open.
- Crash note: campaign-project-service crashed once (10:39 IST) with the SAME signature as the receipt-service
  crashes (access violation in unknown module, offset ...6d12, no hs_err although ErrorFile is set), right after
  rendering a UC PDF. So the crash is not receipt-specific and not caused by C2 (all services already run C1). The
  re-run passed 29/29 without a crash. Suspect the JDK 21.0.12 build / PDFBox font path on this PC; next step is a
  Windows crash dump (needs owner approval) or another JDK build.

### Session 2026-11-02: CSR 1 - compliance pack (document-service, Enterprise)
Owner decisions: CSR is an Enterprise feature for now; each CSR component has its own entitlement
(CSR_COMPLIANCE_PACK, CSR_UTILISATION_CERT, CSR_PROPOSALS; subscription V3) so it can later move to another plan or
become an add-on by data alone. Documents live once in the NGO's vault (object storage) and are linked, never copied.
- document-service V4: vault category CSR (retained like finance records); csr_packs, csr_pack_items (frozen after
  sharing: storage key + SHA-256, DB trigger), csr_pack_views (append-only, no IP stored).
- Pack: company, note, link validity 1-90 days; created as a draft filled automatically from the vault (CsrRules
  recognises registration / PAN / 12A / 80G / CSR-1 / audited accounts / annual report / board / FCRA / UC /
  proposal from titles and tags); add/remove documents; readiness: errors block (deleted or expired paper, empty
  pack), warnings need confirmation (required paper missing, expiring within 30 days).
- Share: freezes each file, opens /api/v1/public/csr/<22-char random code> (no login, noindex, own CSP): NGO name,
  note, documents with type/size/validity/fingerprint, download per file and "Download all" ZIP built on the fly
  (nothing stored); 410 Gone after expiry or "Stop link". Every open/download is logged for the NGO. Vault edits
  after sharing do not change what was sent; the purge job keeps files a live link points to. Copy = new draft.
- Admins only; writing needs the Enterprise flag; reading and stopping links still work after a downgrade.
  Gateway routes /api/v1/ngos/*/csr/** and /api/v1/public/csr/** (sensitive rate limit).
- App: More > CSR documents (admins): packs list with Enterprise card, new pack sheet, pack page (readiness chips,
  issues, documents with kind/validity/fingerprint, vault picker with suggestions + search, share confirmation with
  warnings, link card with share/copy and open/download counts, visits, stop link, copy). ~95 strings en/hi/mr.
- Tests: CsrRulesTest (5); test-csr.ps1 (39 checks); seed-csr.ps1. Regressions: documents 57, receipts 68 (x3).
- Next: CSR 2 utilisation certificate (campaign accounts -> PDF in the vault, NGO + CA sign-off), CSR 3 proposals.

### Session 2026-11-01 (evening): donation receipts R1 + Basic receipt designer R2 (receipt-service live)
Owner decisions: receipts always in English; the donor's name printed exactly as in the donor's profile (Google,
Facebook or e-mail sign-up; donors can edit their name in Profile and the app reminds them before an 80G donation);
numbers PREFIX/<FY>/000001 restarting every financial year; custom design = premium (Basic designer on Professional,
any admin publishes; Enterprise adds approval); background pictures PNG/JPEG only, size-limited (no PDF).
- receipt-service (port 8087, DB receipt, V2): receipts created from donation.paid (online + offline) and by a one-time
  backfill for earlier donations; the number and printed facts are committed first (gap-free series, retries print the
  same receipt), then the PDF is rendered and stored, then the receipt is marked issued. DB triggers keep issued
  receipts immutable and undeletable; corrections are cancel + reissue (new number, both linked); donation voided or
  fully refunded -> receipt cancelled automatically. QR code + 10-character code on every receipt; public page
  /api/v1/public/receipts/verify/<code> shows organisation, number, date, amount, donor initials and VALID/CANCELLED.
- Standard receipt (all plans): A5 landscape, NGO header with logo, Reg./PAN/12A, number + date, donor name, PAN and
  address (80G only), amount with the rupee sign and Indian grouping, amount in words (lakh/crore), payment mode and
  reference, campaign/purpose, 80G statement (verified + valid on the donation date + donor asked with PAN), thank-you
  line, QR, up to 3 signatories (signature image above a line, name, designation). Fonts embedded: DejaVu Sans (text,
  rupee sign) and GNU FreeSans for names in Devanagari or other scripts, shaped by Java2D/HarfBuzz (PDFBox cannot
  join Indic letters).
- Settings (admins): prefix (default from the NGO name), 12A number, thank-you line; signatories with signature images
  (PNG/JPEG, max 1 MB, re-encoded, white paper made transparent); "see a sample receipt".
- Delivery: notification RECEIPT_ISSUED (category ACCOUNT = always sent, en/hi/mr) with a 30-day signed download link
  for guests/offline donors with e-mail; in-app for donors with an account; DELIVERED when the e-mail was sent.
- donation-service: signed-in donors' names are taken from the identity profile on the server (whatever the app sends);
  internal list of paid donations (backfill). document-service V3: receipts appear in Documents > Receipts.
- Basic designer (Professional+): background picture PNG/JPEG only, max 5 MB, at least 1240 px on the short side
  (re-encoded JPEG, page 595 pt wide, height from the picture: landscape = A5, portrait = A4); default layout; fields:
  receipt number, date, donor name/PAN/address, amount, amount in words, payment, towards, NGO name/address/PAN/
  registration/12A, 80G statement, thank-you, verification code, fixed text, signature 1-3, QR code, logo; checks
  (required fields, inside the page, QR at least 45 pt, overlaps); preview with a sample donor; publish = used for new
  receipts; "use standard receipt" switches back. Issued receipts keep the design version they were printed with.
- App: Receipts (summary per FY, search by number/donor, status and FY filters, detail with history, open/share PDF,
  send again, cancel and reissue with reason, retry), Receipt settings + signatories (camera/gallery/file), sample
  receipt, designer overview + editor (tap a field, drag it onto the receipt, arrows, size/width/height/lines/bold/
  alignment/label, add/remove fields, zoom 1-3x, snap to grid, undo, check, preview, publish, change background);
  donor: My receipts (profile + donations list button), receipt detail; donate sheet shows "Your receipt will show:
  <name> - Edit name" (80G: "is this the name on your PAN?"). ~190 strings (hi/mr drafts).
- Seeds/tests: seed-receipts.ps1; test-receipts.ps1 (settings, signatories, online + Devanagari profile name + cash
  receipts, e-mail/in-app, guest link, QR check, register/search/summary, vault, reissue, correction, immutability,
  designer); unit tests ReceiptRulesTest, ImagesTest, PdfRendererTest, TemplateDefinitionTest.
- Stability: one run crashed the receipt-service JVM (native access violation, no report) while rendering; local
  start now gives receipt-service the JDK default 1 MB thread stack (others keep 512k), 320 MB heap, and every service
  writes `logs/hs_err_<service>_<pid>.log` if a JVM ever crashes; non-Latin name shaping is serialised.
  A second identical crash (2026-11-02 02:00, again during test-receipts, again no hs_err) showed the stack size was
  not the cause: all native imaging work of receipt-service (PDF rendering, Java2D fonts/drawing, ImageIO codecs,
  colour management) now runs one job at a time (PdfRenderer.NATIVE_LOCK; the worker and requests used to render
  together). Watch for a third occurrence: if it happens, try without -XX:TieredStopAtLevel=1 and a newer JDK 21.
  Results: test-receipts 68/68 (repeated), test-donations 34/34, test-documents 57/57, test-notifications 26/26.
- Design names: archived designs no longer block reusing a name (V3 partial unique index, NAME_TAKEN check).
- App credit (owner request 2026-11-02): every receipt prints a small line "Generated with Yogdaan - donations and
  accounts app for NGOs" at the foot (standard: after "computer-generated receipt"; custom designs: centred on a white
  strip in the bottom 11 pt). Setting ngo.receipts.branding / RECEIPT_BRANDING (blank = off). The designer marks the
  strip and warns FOOTER_OVERLAP when a field is placed in it. Not yet tied to plans (could be removable on Enterprise).
- Rename (owner decision 2026-11-02): the app is now **Yogdaan** (domain yogdaan.co.in). "Seva Setu" is used by several
  state-government portals and an existing donor app. Changed: app name/label, en/hi/mr strings (hi/mr: योगदान),
  receipts (credit "Generated with Yogdaan (yogdaan.co.in) ..."), verification and campaign share pages, account
  sheet, minutes PDF, OTP SMS text (must be DLT-registered under the new name before real SMS), e-mail sender name,
  login page title (realm displayName). Stored e-mail templates renamed by notification V8 (V2-V7 left unchanged
  for Flyway checksums); tools/gen_templates.py updated. No logo image exists yet (default Flutter icon).
- Master configuration: config/yogdaan.env.example (committed template) -> config/yogdaan.env (gitignored, real
  values): addresses, e-mail SMTP, Google/Facebook sign-in keys, Keycloak admin, DB password, secrets, storage,
  payments, billing, SMS. start-local.ps1 loads it (only lines with a value); scripts/configure-login.ps1 applies
  title, SMTP and Google/Facebook identity providers to Keycloak. Server: /etc/yogdaan/yogdaan.env (systemd).
- Fix (found 2026-11-02 00:15 IST): "today" was the UTC date in donation-service (offline donation date, reconciliation)
  and ngo-registry-service (registration/expiry dates, 80G validity), so a cash donation entered between midnight and
  05:30 IST was refused as "in the future". These now use India time, like campaign-service already did.
- Next: Advanced designer screens (several templates, conditions, approval), Form 10BD/10BE export for the CA,
  receipts for in-kind donations with valuation notes.

## Next steps (in order) - agreed with the owner 2026-10-29
Done since the original plan: first emulator runs, core services built and UI-tested (phase 1), notification-service.

**A. Accounts, sign-in and profile (next)**
1. Social sign-in via Keycloak identity brokering: Google, Facebook, Sign in with Apple (required on iOS by App Store
   guideline 4.8 when Google/Facebook are offered). App buttons "Continue with Google/Facebook/Apple" use
   `kc_idp_hint` so donors go straight to the provider; first-broker-login creates the account automatically and
   links to an existing account with the same verified e-mail. Keycloak page kept only as a branded fallback
   (Yogdaan theme en/hi/mr). Owner action: create Google Cloud OAuth client, Meta developer app, Apple service ID,
   and publish a privacy policy page. Local testing with test apps/accounts. (~1 day)
2. [DONE 2026-10-29] "Complete your profile" after first sign-in (profile picture, name, +91 mobile, preferred language saved to the server - also used
   for e-mail language, date of birth) and "My profile" under Settings; Facebook accounts without e-mail are asked
   for one. (~1.5 days with the profile picture)
   PROFILE PICTURE (owner decision 2026-10-29: optional for everyone - staff, admins and donors; shown with the
   name in NGO activity):
   - Three choices, same for all users: own photo, one of a set of built-in avatar icons (to hide one's real look),
     or nothing (initials circle). "Skip" on the profile screen; changeable any time in My profile.
   - Social sign-in photo is offered, not applied silently ("Use my Google photo?"); copied into our storage, not
     hot-linked. Own photo via camera or gallery with square crop in the app.
   - Built-in avatars: ~12-16 neutral illustrated icons shipped with the app (original artwork, no third-party
     characters), stored as an avatar code - no image upload, no personal data.
   - Upload rules: JPEG/PNG/WebP checked by content, max 5 MB in, re-encoded server-side to 512px + 128px
     thumbnails, EXIF/GPS metadata stripped, stored in object storage (local files now, S3-compatible in cloud),
     served with short-lived links; initials avatar fallback; deleted with the account.
   - Where shown: NGO team list, activity feed/audit views ("Sunil recorded a donation"), staff assignments,
     help-desk replies. Public places (supporter walls, campaign pages) only if the donor opts in; otherwise
     "Anonymous supporter".
   - Misuse: "report photo" for NGO admins/platform support; support can remove a photo (audited) and ask for a new one.
   GENDER (owner decision pending; recommendation): optional, default "Prefer not to say"; options Female, Male,
   Transgender (term used by the Transgender Persons Act 2019), Prefer not to say. Purpose stated on screen. Never
   shown to NGOs per person; used only in aggregated reports with a minimum group size (e.g. 10) and, with explicit
   consent, for programmes that need it (e.g. women-only volunteering). Not required for 80G receipts. Messages use
   the respectful plural (aap/tumhi) so Hindi/Marathi text never depends on gender. Changeable/erasable any time.
   18+ ONLY (owner decision 2026-10-29: no under-18 accounts, no parental-consent path):
   - Age gate enforced by identity-service, not only the app: until a date of birth with age >= 18 is saved, every
     API except /me, profile completion and account deletion answers 403 PROFILE_INCOMPLETE (social sign-in
     cannot bypass it). Age computed in Asia/Kolkata.
   - Under 18: friendly refusal screen; the just-created Keycloak user and identity record are deleted at once and
     no personal data is kept (DPDP: no child data without parental consent). Audit row without personal data.
   - Date of birth is set once and cannot be edited in the app (corrections via support, audited); never shown to
     NGOs; "I confirm I am 18 or older" in the terms acceptance.
   - Applies to every account type (donor, NGO staff/admin). NGO-recorded donors/beneficiaries who never log in
     are NGO records, not app accounts (separate DPDP guidance for NGOs).
   - Store listings: 18+ age rating on Google Play and the App Store; not designed for children (Play Families
     policy not applicable).
3. Donor tax details captured only when needed: PAN + address at the first donation that wants an 80G receipt,
   stored in donor-service (DPDP data minimisation). (~0.5 day)
4. Referral and ad attribution: links `yogdaan.co.in/r/<code>` and `yogdaan.co.in/c/<campaign>?utm_*`, Android App
   Links / iOS Universal Links, Google Play Install Referrer for new installs (Firebase Dynamic Links shut down
   25 Aug 2025), attribution saved on the donor profile at first sign-in, "where donors came from" report. (~1.5 days)
5. Account deletion completed end to end (today only a request flag is stored): sign-in blocked on request, 7-30 day
   grace period with cancel + confirmation e-mail, then a processor deletes the Keycloak user (removes Google/Facebook
   links) via the admin API, erases/anonymises personal data in identity, donor and notification services, ends
   memberships; donations and issued 80G receipts are retained as the tax law requires, de-linked from the profile;
   final e-mail; audit entry without personal data. Meta data-deletion callback starts the same process. (~1.5-2 days)
6. Notification opt-out completion: one-click unsubscribe link + List-Unsubscribe header in every non-mandatory
   e-mail (fills the existing `email_opt_outs` table; Gmail/Yahoo bulk-sender rule), consent-based defaults
   (campaign/marketing updates OFF until the donor opts in; transactional ON), a "pause all non-essential
   notifications" master switch, push per category + OS permission once push exists, unsubscribe also from the
   NGO-side donor record (existing donor-service preference). (~1 day)
   Already correct and unchanged: NGO admin "remove member" ends only that NGO membership (account kept, seat freed,
   audited; primary/last admin protected; re-invite required).

7. Standard test dataset (owner rule 2026-10-29): every feature is developed and tested with at least 2 NGO admins,
   2 staff and 3 donors. Idempotent `scripts/seed-dataset.ps1`: Keycloak users + completed profiles + memberships via
   the API. Asha Seva: Asha (primary admin), Ravi (secondary admin), Sunil (staff), Priya (finance staff);
   donors Meera, Kiran, Anil (new). Phase 2 adds donor records/donations for all three. E2E scripts use this set.
8. Mobile verification + contact privacy (owner rule 2026-10-29):
   - A mobile number is saved only after SMS OTP verification (6 digits, hashed, 5-min expiry, 5 attempts, resend
     after 60 s, daily caps per number and per account); changing it needs a new OTP; unverified numbers are never
     stored on the profile.
   - SMS adapter interface; local dev adapter delivers the code to Mailpit/log; production provider (MSG91, Gupshup,
     Twilio...) needs TRAI DLT registration (entity, sender header, approved OTP template) - owner action.
   - Visibility: the phone is personal - shown only to the person, to the active members of an NGO the person belongs
     to (team directory, STAFF_VIEW), and to platform support. A donor who joins an NGO as staff becomes visible to
     that NGO's team only; other NGOs and donors never see it. Every reveal of a contact is audited.

**B. Phase 2 - donations**
6. Run and fix donor, donation-payment, receipt, campaign services; donation checkout (mock gateway) and receipts in
   the app; receipt e-mails through notification-service (`/internal/v1/notifications`, resourceType RECEIPT).

**C. Remaining services**
7. ngo-operations (volunteers, beneficiaries, expenses), helpdesk, reporting; push notification adapter.

**D. Quality and release**
8. Unit/integration tests towards the 1,000-case target (pricing, state machines, ledger, receipt numbering, seats).
9. iOS project (`flutter create --platforms=ios`), iPhone-specific setup; build on a Mac or cloud macOS runner.
10. Cloud stage 1 (single domain: `yogdaan.co.in`, `api.` and `auth.` subdomains; one VM, systemd services, Caddy with
    HTTPS, nightly PostgreSQL backups), consider merging services into 2-3 deployables to cut RAM; CI; Play release.
11. Kubernetes/OpenShift manifests, SBOM, licence inventory, release docs (when usage grows).

## Known blockers / decisions
- MinIO is AGPL → SeaweedFS (Apache-2.0) used for S3-compatible storage.
- Real payment gateway & SaaS billing provider: not integrated (mock only); app-store billing policy review pending (spec §31).
- iOS build/simulator requires macOS + Xcode — not available on this Windows PC.
- Devanagari text in receipt PDFs needs Noto Sans fonts in `ngo.receipts.font-dir`; PDFBox lacks complex shaping.
- App name: Yogdaan (renamed from "Seva Setu" on 2026-11-02; domain yogdaan.co.in bought by the owner).

## Session 2026-10-28
- Old Podman pods pod_ngo-platform / pod_ngo-v1-foundation removed (volumes kept).
- **Mobile app builds and runs** on emulator `ngo_pixel` (language select -> welcome screens verified).
  - Gradle 9.3.1 pre-downloaded with curl to D:\tools\_dl (wrapper uses file:// URL).
  - gradle.properties: heap 2g, no daemon, `kotlin.incremental=false`, `kotlin.compiler.execution.strategy=in-process`
    (project on D: + pub cache on C: crashes Kotlin incremental caches).
  - Fixes: amountRange placeholders -> String; file_picker 13 API (`FilePicker.pickFile()` + `readAsBytes()`).
  - run-mobile.ps1: build success judged by APK timestamp; skips build when APK is newer than sources;
    starts the emulator only after the build (RAM); `install-app.cmd` = install without build.
- **Backend: all 14 modules compile** (`mvn verify -DskipTests`). Fixes: `ApiException.code()`; receipt Template `@jakarta.persistence.Version`.
- Remote-session helper: `scripts\agent.cmd` watches `D:\ngo-platform\.agent\cmd-*.ps1` and runs them (close window to stop).
- PC limits: RAM is the bottleneck. Debug app shows "isn't responding" dialogs while the PC is loaded. Podman machine is
  stopped after reboot (start Podman Desktop / `podman machine start`, then start-infra.cmd).
- Next: run unit tests; start infra + gateway/identity/registry and test Keycloak login from the app;
  decide dev topology (Eureka/no-Podman question still open with the owner); remaining services
  (operations, helpdesk, notification, reporting); donation/receipt/campaign screens.

### Afternoon: container-free local stack (owner decision: option 2, no RabbitMQ locally, Eureka yes)
- Podman is no longer used for development (machine stopped; old `ngo-infra` pod kept but unused).
- Native, open-source tools in D:\tools: PostgreSQL 17.11 (portable, data in `.local\pgdata`, superuser
  password dev-only), Keycloak 26.7.2 (`start-dev`, H2 store, realm imported from infrastructure/keycloak),
  Mailpit (SMTP 1025, UI 8025). Scripts: `setup-local.cmd` (one-time), `start-local.cmd`, `stop-local.cmd`.
- New module `services/discovery-server` (Eureka, port 8761, loopback only). Services register by
  spring.application.name; internal REST clients use `http://<service-name>` (Eureka load balancer when
  `ngo.discovery.enabled`, cluster DNS in Kubernetes); the gateway routes `lb://<service-name>`.
- Events: `ngo.events.transport=http` locally - `HttpOutboxRelay` POSTs outbox events to subscribers'
  `/internal/v1/events` (routing table `ngo.events.routes` in commons-defaults.yml, mirrors the RabbitMQ
  bindings); listeners implement `EventHandler`. Production keeps RabbitMQ (`EVENTS_TRANSPORT=rabbit`,
  `AUTOCONFIG_EXCLUDE=`). Storage: `LocalObjectStorage` (`STORAGE_TYPE=local`) / S3 in production.
- Cloud env overrides are documented at the top of commons-defaults.yml.
- Runtime fixes: CHAR(n) columns mapped with `@JdbcTypeCode(SqlTypes.CHAR)`; gateway bean name clash
  (`routeLocator`). Static entity-vs-migration check: 75 entities / 745 fields consistent.
- Verified end-to-end: Keycloak password-grant token (ngo-admin-cli) -> gateway -> Eureka -> identity-service
  `/api/v1/me` returns the provisioned user; public plans/app-config via gateway OK; app onboarding ->
  Sign in opens the Keycloak login page in Chrome on the emulator (10.0.2.2:8180).
- Startup on this laptop: Keycloak ~2 min, each service ~3.5 min (RAM-bound). Core set running:
  discovery, gateway, identity, registry, subscription.
- Next: sign in from the app (manual), NGO registration flow end-to-end, unit tests, remaining services,
  donation/receipt/campaign screens, branded splash screen (still Flutter default).

### UI test session (2026-10-28 afternoon) - findings
Test data: `infrastructure/keycloak/test-users.json` + `scripts/seed-test-users.ps1` (Priya/Sunil/Kiran, DEV ONLY);
sample documents pushed to the emulator's Download folder (clearly marked SAMPLE TEST DOCUMENT).
Emulator now reaches the backend as localhost (adb reverse 8080/8180, done by run-mobile.ps1); app defaults
API http://localhost:8080 and issuer http://localhost:8180/realms/ngo.
Bugs found by the UI test and fixed:
1. AndroidManifest `android:taskAffinity=""` (Flutter template) split the AppAuth redirect into another task ->
   "AppAuth: No stored state", login silently lost. Removed.
2. Realm users lacked `offline_access` -> Keycloak CODE_TO_TOKEN_ERROR "Offline tokens not allowed". Realm file
   updated; `scripts/fix-offline-access.ps1` patches a running realm (also adds it to default-roles-ngo).
3. Gateway missing route `/api/v1/public/reference` (registration wizard reference data). Added; new checker
   `scripts/check-gateway-routes.py` (all controller paths vs gateway routes: 0 mismatches).
4. Login page swallowed the sign-in exception (generic snackbar only) - TODO: log cause in debug builds.
5. Launcher icon was the Flutter default (also shown on the Android 12+ splash) - replaced with a placeholder brand icon.
Tooling: `scripts/restart-service.ps1 -Name <svc> [-Build]`; `scripts/ui.ps1` (adb helpers: TapText, WaitFor, Shot).
Verified in the UI: onboarding -> sign in (Asha) -> NGO home -> registration step 1 saved on the server.
Environment limit: 7.7 GB RAM laptop; debug APK on the emulator ANRs under load -> switched to `run-mobile.ps1 -Mode profile`.

### UI test session (2026-10-28 evening) - phase 1 end to end (NGO onboarding, verification, trial, staff)
Passed in the UI (profile build, emulator): Asha registers "Asha Seva" (6-step wizard, 4 documents) -> submit
(PENDING) -> platform admin queue -> review -> Approve -> registry outbox delivers `ngo.verification.approved`
(1 retry) -> subscription starts a 30-day PROFESSIONAL trial -> Asha's home shows the trial card -> Asha invites
sunil.staff@ngo.test (Staff) -> Sunil signs in, sees the invitation, accepts -> lands on the NGO home ->
More menu hides Subscription for Staff. Plans page and Marathi language switch verified.
Bugs found and fixed (app):
6. Admin verification console had no Settings/Sign out (Back exited the app) -> app-bar actions added.
7. Admin could not open uploaded documents -> tap a document to download `/documents/{id}/content` and open it.
8. Raw codes shown (`PAN_CARD`, `PUBLIC_TRUST`, `TRIAL`, role names) -> `core/util/labels.dart`, ARB `statusLabel`,
   `roleName` selects (en/hi/mr); StatusBadge localizes UPPER_SNAKE codes; "(optional)" stripped in summaries.
9. Quick actions gated on `verificationStatus == 'VERIFIED'` but the backend value is `APPROVED` -> always disabled.
10. After sign-out/sign-in, `/me` reload kept the signed-out `null` value, router sent members to /ngo/start.
    Router now waits for the real profile and sends members on /ngo/start to /ngo/home (`?stay=1` keeps them).
11. Quick actions ignored permissions (Staff saw "Invite staff"/"New campaign") -> each tile gated by permission.
12. Wizard step chips did not follow the current step -> auto-scroll; registered users get a quiet "Register your
    NGO" link instead of the hero card; approved registration card opens the NGO.
Open findings (not fixed yet):
- Invitation card shows only the role, not the NGO name (identity has no NGO name; add `ngoName` to invitations).
- Secondary admin entered in the wizard (Ravi) is stored as a contact only; no invitation is created for him.
- No invitation email locally (notification-service not running).
- Display name "Asha Seva" / description truncated by adb typing (test artefact, not an app bug).
Emulator/PC notes: host RAM is the hard limit (0.1 GB free with 5 services + Keycloak + emulator). Chrome Custom Tab
ANR/crashed with `-gpu auto`; emulator now starts with `-gpu swiftshader_indirect`. adb `input text` drops characters
under load -> `scripts/login.ps1` types one character at a time. Gboard stylus prompt disabled on the emulator.
Phase 2 (donors, donations, receipts, campaigns) needs donor/donation/receipt/campaign services, which do not fit in
RAM next to the emulator on this PC: run phase 2 API-level without the emulator, or on a bigger machine/cloud.
