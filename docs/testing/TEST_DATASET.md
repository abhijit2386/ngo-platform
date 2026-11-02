# Standard test dataset (DEV/TEST ONLY - fictional people)

Owner rule (2026-10-29): every feature is developed and tested with at least **2 NGO admins, 2 staff and 3 donors**,
so permission, privacy and consistency problems show up early. Restore it any time:

    powershell -File D:\ngo-platform\scripts\seed-dataset.ps1

NGO: **Asha Seva** (`0cf945f6-1183-42f5-aeb1-20bab9be302b`, verified, Professional trial).

| Person | Login | Password | Role | Language | Mobile (verified) |
|---|---|---|---|---|---|
| Asha Patil | asha.admin@ngo.test | DevNgo#2026 | Primary admin | en | 9000000101 |
| Ravi Kulkarni | ravi.staff@ngo.test | DevStaff#2026 | Secondary admin | hi | 9000000102 |
| Sunil More | sunil.staff@ngo.test | DevTest#2026 | Staff | en | 9000000103 |
| Priya Deshmukh | priya.admin@ngo.test | DevTest#2026 | Finance (staff) | mr | 9000000104 |
| Meera Iyer | meera.donor@ngo.test | DevDonor#2026 | Donor | en | 9000000201 |
| Kiran Joshi | kiran.donor@ngo.test | DevTest#2026 | Donor | mr | - |
| Anil Shah | anil.donor@ngo.test | DevTest#2026 | Donor | hi | - (test-phone adds/removes) |
| Platform admin | platform.admin@ngo.test | DevAdmin#2026 | Platform staff | en | - |

The variety is deliberate: three languages, donors with and without a phone, both admin types, two staff roles.
Throw-away accounts used by tests: `adult.test`, `teen.test`, `eighteen.test`, `newstaff.test`, `outsider.test`
(@ngo.test). SMS codes (development adapter) and e-mails land in Mailpit: http://localhost:8025.

End-to-end scripts (run after the seed): `test-profile.ps1`, `test-phone.ps1`, `test-notifications.ps1`,
`test-campaigns.ps1` (removes its own `E2E ...` campaigns first).

## Sample campaigns (`seed-campaigns.ps1`, idempotent by title)
| Campaign | Created by | State | Notes |
|---|---|---|---|
| School books for 200 children | Sunil (staff) | Live (approved by Asha) | cover, 3 test donations (Rs 19,600 of 50,000) |
| Free health camp for elders | Asha (admin) | Live (published directly) | cover, 2 test donations |
| Monsoon relief kits for 80 families | Sunil | Awaiting approval | cover; starts in 2 days |
| Clean drinking water for Wadi village | Priya (finance) | Draft | no cover |

Campaign rule (owner, 2026-10-29): any team member drafts and sends for approval; only admins approve/publish,
request changes, edit a live campaign, close or archive it. Test donations (`CAMPAIGN_TEST_DONATIONS=true`, set by
start-local) stand in for online payments until donation-payment-service exists.

## Online donations (`seed-donations.ps1`, idempotent)
Asha Seva gets a fictional 80G registration (valid to 31 Mar 2031) and online payments with the TEST provider
(no money moves; the app shows a "Test payment" screen). Donors Meera, Kiran and Anil can donate; the NGO's own team
(Asha, Ravi, Sunil, Priya) cannot (owner rule). `test-donations.ps1` uses its own "E2E" campaign and cleans up.

## Campaign accounts (`seed-expenses.ps1`, idempotent)
"School books for 200 children" gets a budget (Notebooks 20,000, Textbooks 18,000, School bags 10,000,
Transport 2,000), three vendors (Sharma Stationers, Pune Book Depot, Patil Tempo Service), a Rs 3,000 cash advance
to Sunil, and expenses added by Sunil with bill photos: notebooks Rs 9,600 (approved by Asha), textbooks Rs 14,500
(approved by Ravi), tempo Rs 1,200 paid by Sunil from his advance (approved), school bags Rs 6,000 (waiting for
approval) and tea Rs 450 without bill (sent back by Asha). Expenses are approved by administrators only (owner rule);
Priya (finance) can record but not approve. `test-expenses.ps1` uses its own "E2E" campaign and cleans up.

## Document vault and meetings (`seed-documents.ps1`, idempotent by title)
| Item | Who | Notes |
|---|---|---|
| FCRA renewal acknowledgement | Asha | Tax & compliance, expires in 20 days (expiry warning) |
| Audit report FY 2025-26 | Priya | Finance, whole team |
| Volunteer guidelines 2026 | Sunil | Other, whole team |
| Health camp, Wadi (photo) | Sunil | Photos & reports |
| Office rent agreement 2026-27 | Ravi | Agreements, confidential by default (Ravi + admins) |
| Registration documents, expense bills | - | listed automatically from registration and campaign expenses |
| Annual General Meeting 2026 (20 Sep 2026) | Asha | minutes approved, 2 numbered resolutions, minutes PDF in Documents |
| Board meeting - October | Asha | approved; action items for Sunil (quotations) and Priya (account sheet) |
| Board meeting - November (yesterday) | Asha | draft minutes: waits for approval; action item for Ravi |
| Staff meeting - campaign planning (+3 days), Health committee (+10 days) | Asha | upcoming |

## Donation receipts (`seed-receipts.ps1`, idempotent)
| Item | Value |
|---|---|
| Prefix / numbers | ASF/<FY>/000001, restarting every 1 April |
| 12A number | AAATA1234BE20214 (fictional) |
| Thank-you line | Thank you for standing with the children of Wadi. |
| Signatories | Asha Patil (Chairman), Ravi Kulkarni (Secretary), both with signature images |
| Receipts | issued automatically for every paid donation (earlier seed donations by backfill) |
| Designer | Asha Seva is on the Professional trial: Basic designer available (test-receipts creates and archives an E2E design) |

## CSR papers (`seed-csr.ps1`, idempotent, after `seed-documents.ps1`)

| What | Value |
|---|---|
| Vault papers | PAN card - Asha Seva Foundation; 80G approval order and 12A registration order (valid till 31 Mar 2031); Trust registration certificate (admins only); CSR-1 registration (MCA); Board of trustees 2026; Annual report 2025-26. From seed-documents: Audit report FY 2025-26, FCRA renewal acknowledgement (expiring) |
| Plan | CSR packs are Enterprise. test-csr switches the Professional trial to Enterprise and back (entitlement cache: 30 s) |
| Demo pack | "Compliance documents FY 2026-27" for Sahyadri Industries CSR Foundation (fictional), shared from the app on 2 Nov 2026 |
| E2E | test-csr creates "E2E ..." packs and "E2E CSR ..." vault files and removes them at the start and end |
| Utilisation certificate | Demo UC/2026-27/004 for Sahyadri Industries CSR Foundation on "School books for 200 children" (grant Rs 50,000, 24 Oct - 2 Nov 2026), signed by Asha Patil (Chairperson), certified by test CA Meena Joshi, M. No. 123456, UDIN 26123456ABCDEF1234 (fictional) |
| Funding proposal | Demo PR/2026-27/009 to Sahyadri Industries CSR Foundation (Attn. Ms. Kavita Rao, CSR Head) for "School books for 200 children", ask Rs 2,50,000 for 12 months, 200 people, compliance pack attached; sent 2 Nov 2026 with a note; funded Rs 2,00,000 (ref SCF/2026/14) |
| Proposal E2E | test-proposals creates "E2E ..." proposals (and a linked UC) and removes them and their vault files at the start and end; it uses the shared pack from seed-csr.ps1 |
| UC E2E | test-uc creates "E2E ..." certificates on "School books for 200 children" and removes them (and their vault files) at the start and end; it needs the approved expenses from seed-expenses.ps1 |
