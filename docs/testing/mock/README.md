# Test-server mock data (OCI test4, loaded 3 Nov 2026)

TEST DATA ONLY. Every person, phone number, PAN and amount here is made up. These passwords work only on the
test server; never reuse them on production.

Loaded by `deploy/oci/seed-demo.py` (see `deploy/oci/README.md`, section 3a). Accounts: `demo-accounts.txt`.

## Accounts

| Who | Email | Password |
|---|---|---|
| Platform admin | platform.admin@yogdaan.test | Yogdaan#Admin26 |
| NGO team (19) | firstname.lastname@<ngo>.test | Yogdaan#Team26 |
| Donors (36) | firstname.lastname@donor.test | Yogdaan#Donor26 |

Team members have verified phones 91000NXXXX (N = NGO number 0-5, XXXX = member 0000-0003).

## Expected data (use this to check a database migration)

| NGO | Team | 80G | Campaigns (both live) |
|---|---|---|---|
| Asha Shiksha (Pune) | 4 (incl. FINANCE) | yes | School kits for 300 children; Evening library for Hadapsar slum |
| Jeevan Arogya (Nagpur) | 3 | yes | Cataract surgeries for 100 elders; Monthly medicine bank |
| Harit Dhara (Bengaluru) | 3 | yes | 10,000 native trees; Clean up Bellandur lake edge |
| Annapurna Bhojan (Jaipur) | 3 | no | Hospital meals for a month; Ration kits for 200 families |
| Sakhi Mahila (Indore) | 3 | yes | Sewing machines for 50 women; Digital skills lab |
| Pashu Raksha (Ahmedabad) | 3 | no | Ambulance for injured animals; Rabies vaccination drive |

All 6 NGOs: APPROVED, public, trial subscription, TEST payment provider (MOCK) active.

Paid donations (database `donation`):

| Channel | Count | Total (Rs) |
|---|---|---|
| ONLINE (test provider) | 72 | 2,25,622.00 |
| CASH | 6 | 9,000.00 |
| CHEQUE | 6 | 60,000.00 |
| UPI_DIRECT | 6 | 4,500.00 |
| **Total** | **90** | **2,99,122.00** |

Offline references: DEMO-<NGO>-1 (cash), -2 (cheque), -3 (UPI), e.g. DEMO-ASHA-2.

## After a database migration

1. Counts must match the table above:
   `sudo -u postgres psql -d donation -tAc "select channel, count(*), sum(amount) from donations where status='PAID' group by channel"`
2. Run `sudo python3 seed-demo.py` again on the server. It reuses existing accounts, NGOs and campaigns, does not add
   donations again (state file `/root/yogdaan-demo-state.json`), and ends with `RESULT pass=N fail=0` when sign-in,
   NGOs, teams, campaigns, donation history and receipts all still work. If the state file was lost with the old server,
   copy it back first (content: `{"donations_done": true, "online": 72, "offline": 18}`) or donations are added twice.
3. In the app: sign in as a donor (history + receipts), an NGO admin (donations, reports) and the platform admin.
