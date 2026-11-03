#!/usr/bin/env python3
"""TEST SERVER ONLY: demo data for a Yogdaan test install (fictional NGOs, people, PANs and money).

Run on the server:  sudo python3 seed-demo.py
- 1 platform admin, 6 NGOs (registered, documents uploaded, submitted, approved), 3 team members each,
  2 campaigns per NGO (approved, with cover), 36 donors with completed profiles,
  online donations through the TEST payment provider (needs PAYMENTS_MOCK_ENABLED=true) and offline cash/cheque.
- Uses the apps' own APIs, so business rules run as for a real user.
- A temporary password-grant client (ngo-admin-cli) is created and removed at the end.
- Re-runnable: existing users/NGOs/campaigns are reused; donations are added only once (state file).
Credentials are written to /root/yogdaan-demo-credentials.txt.
"""
import json, os, random, struct, sys, time, urllib.error, urllib.parse, urllib.request, uuid, zlib
from datetime import date, timedelta

API = "http://127.0.0.1:8080"
KC = "http://127.0.0.1:8180"
MAILPIT = "http://127.0.0.1:8026"
STATE = "/root/yogdaan-demo-state.json"
CREDS = "/root/yogdaan-demo-credentials.txt"
PW_ADMIN, PW_TEAM, PW_DONOR = "Yogdaan#Admin26", "Yogdaan#Team26", "Yogdaan#Donor26"
random.seed(26)


def env():
    out = {}
    for line in open("/etc/yogdaan/yogdaan.env", encoding="utf-8"):
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        out[k.strip()] = v.strip().strip('"').strip("'")
    return out


E = env()
log_lines = []


def log(*a):
    s = " ".join(str(x) for x in a)
    print(s, flush=True)
    log_lines.append(s)


class ApiError(Exception):
    def __init__(self, status, body):
        super().__init__(f"{status}: {body[:400]}")
        self.status, self.body = status, body
        try:
            self.code = json.loads(body).get("code")
        except Exception:
            self.code = None


def http(method, url, token=None, body=None, form=None, headers=None, raw=None, ctype=None):
    h = dict(headers or {})
    data = None
    if token:
        h["Authorization"] = "Bearer " + token
    if body is not None:
        data = json.dumps(body).encode("utf-8")
        h["Content-Type"] = "application/json; charset=utf-8"
    elif form is not None:
        data = urllib.parse.urlencode(form).encode()
        h["Content-Type"] = "application/x-www-form-urlencoded"
    elif raw is not None:
        data, h["Content-Type"] = raw, ctype
    req = urllib.request.Request(url, data=data, method=method, headers=h)
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            t = r.read().decode("utf-8")
            loc = r.headers.get("Location")
            if not t:
                return {"__location": loc} if loc else None
            return json.loads(t)
    except urllib.error.HTTPError as e:
        raise ApiError(e.code, e.read().decode("utf-8", "replace"))


def api(method, path, token, body=None):
    return http(method, API + path, token, body)


def multipart(path, token, fields, filename, content, ctype):
    b = "----yogdaan" + uuid.uuid4().hex
    parts = []
    for k, v in fields.items():
        parts.append(f'--{b}\r\nContent-Disposition: form-data; name="{k}"\r\n\r\n{v}\r\n'.encode())
    parts.append(f'--{b}\r\nContent-Disposition: form-data; name="file"; filename="{filename}"\r\n'
                 f"Content-Type: {ctype}\r\n\r\n".encode() + content + b"\r\n")
    parts.append(f"--{b}--\r\n".encode())
    return http("POST", API + path, token, raw=b"".join(parts), ctype="multipart/form-data; boundary=" + b)


def png(c1, c2, w=960, h=540):
    def hx(c):
        return [int(c[i:i + 2], 16) for i in (1, 3, 5)]
    a, b = hx(c1), hx(c2)
    rows = []
    for y in range(h):
        row = bytearray([0])
        for x in range(w):
            t = (x / w * 0.65 + y / h * 0.35)
            row += bytes(int(a[i] + (b[i] - a[i]) * t) for i in range(3))
        rows.append(bytes(row))

    def chunk(tag, data):
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(b"".join(rows), 6)) + chunk(b"IEND", b""))


def pdf(title):
    text = f"TEST DOCUMENT - {title} - fictional, for the Yogdaan test server only"
    objs = ["<< /Type /Catalog /Pages 2 0 R >>", "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>",
            None, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"]
    stream = f"BT /F1 14 Tf 50 780 Td ({text}) Tj ET"
    objs[3] = f"<< /Length {len(stream)} >>\nstream\n{stream}\nendstream"
    out, offs = "%PDF-1.4\n", []
    for i, o in enumerate(objs, 1):
        offs.append(len(out))
        out += f"{i} 0 obj\n{o}\nendobj\n"
    x = len(out)
    out += f"xref\n0 {len(objs) + 1}\n0000000000 65535 f \n" + "".join(f"{o:010d} 00000 n \n" for o in offs)
    out += f"trailer\n<< /Size {len(objs) + 1} /Root 1 0 R >>\nstartxref\n{x}\n%%EOF\n"
    return out.encode("latin-1")


def day(n):
    return (date.today() + timedelta(days=n)).isoformat()


# ---------------------------------------------------------------- data (all fictional)
NGOS = [
    dict(key="asha", legal="Asha Shiksha Foundation", display="Asha Shiksha", type="PUBLIC_TRUST", city="Pune",
         district="Pune", state="Maharashtra", pin="411004", causes=["EDUCATION", "CHILD_WELFARE"], g80=True,
         pan="AAATA1001A", darpan="MH/2016/0100101", color="#0B7A4B",
         desc="Runs evening study centres and libraries for children of daily-wage workers in Pune.",
         team=[("Asha", "Kulkarni", "PRIMARY_ADMIN"), ("Rohan", "Deshpande", "SECONDARY_ADMIN"), ("Sneha", "Pawar", "STAFF"), ("Vikram", "Joshi", "FINANCE")],
         campaigns=[("School kits for 300 children", "EDUCATION", "75000", "Notebooks, bags and geometry boxes for the new school year.", "#0B7A4B", "#7BC67E"),
                    ("Evening library for Hadapsar slum", "EDUCATION", "150000", "Books, shelves and a part-time librarian for one year.", "#0E7490", "#67E8F9")]),
    dict(key="jeevan", legal="Jeevan Arogya Seva Society", display="Jeevan Arogya", type="SOCIETY", city="Nagpur",
         district="Nagpur", state="Maharashtra", pin="440010", causes=["HEALTH", "ELDERLY_CARE"], g80=True,
         pan="AAAAJ2002B", darpan="MH/2012/0200202", color="#C2410C",
         desc="Free health camps, medicines and eye surgeries for elders in villages around Nagpur.",
         team=[("Meenal", "Wagh", "PRIMARY_ADMIN"), ("Sanjay", "Bhosale", "SECONDARY_ADMIN"), ("Kavita", "Shinde", "STAFF")],
         campaigns=[("Cataract surgeries for 100 elders", "HEALTH", "250000", "Each Rs 2,500 restores sight for one senior citizen.", "#C2410C", "#FDBA74"),
                    ("Monthly medicine bank", "ELDERLY_CARE", "60000", "Blood-pressure and diabetes medicines for 120 elders.", "#B45309", "#FCD34D")]),
    dict(key="harit", legal="Harit Dhara Environment Trust", display="Harit Dhara", type="PUBLIC_TRUST", city="Bengaluru",
         district="Bengaluru Urban", state="Karnataka", pin="560034", causes=["ENVIRONMENT", "WATER_SANITATION"], g80=True,
         pan="AAATH3003C", darpan="KA/2018/0300303", color="#15803D",
         desc="Restores lakes and plants native trees with schools and resident groups in Bengaluru.",
         team=[("Lakshmi", "Rao", "PRIMARY_ADMIN"), ("Arjun", "Hegde", "SECONDARY_ADMIN"), ("Divya", "Shetty", "STAFF")],
         campaigns=[("10,000 native trees", "ENVIRONMENT", "200000", "Saplings, tree guards and two years of watering.", "#15803D", "#86EFAC"),
                    ("Clean up Bellandur lake edge", "WATER_SANITATION", "90000", "Nets, gloves and waste pickup for 12 weekend drives.", "#1D4ED8", "#93C5FD")]),
    dict(key="annapurna", legal="Annapurna Bhojan Seva Foundation", display="Annapurna Bhojan", type="SECTION_8_COMPANY",
         city="Jaipur", district="Jaipur", state="Rajasthan", pin="302017", causes=["HUNGER_NUTRITION"], g80=False,
         pan="AABCA4004D", darpan="", color="#B91C1C",
         desc="Serves hot meals at government hospitals and ration kits to families in need in Jaipur.",
         team=[("Pooja", "Sharma", "PRIMARY_ADMIN"), ("Manish", "Agarwal", "SECONDARY_ADMIN"), ("Neha", "Meena", "STAFF")],
         campaigns=[("Hospital meals for a month", "HUNGER_NUTRITION", "120000", "Two hot meals a day for patients' families at SMS Hospital.", "#B91C1C", "#FCA5A5"),
                    ("Ration kits for 200 families", "HUNGER_NUTRITION", "180000", "Rice, dal, oil and spices for one month per family.", "#9A3412", "#FDBA74")]),
    dict(key="sakhi", legal="Sakhi Mahila Vikas Sanstha", display="Sakhi Mahila", type="SOCIETY", city="Indore",
         district="Indore", state="Madhya Pradesh", pin="452001", causes=["WOMEN_EMPOWERMENT", "LIVELIHOOD"], g80=True,
         pan="AAAAS5005E", darpan="MP/2015/0500505", color="#7C3AED",
         desc="Tailoring, computer and bookkeeping courses that help women in Indore earn their own income.",
         team=[("Sunita", "Verma", "PRIMARY_ADMIN"), ("Rekha", "Choudhary", "SECONDARY_ADMIN"), ("Anjali", "Tiwari", "STAFF")],
         campaigns=[("Sewing machines for 50 women", "LIVELIHOOD", "100000", "A machine and a three-month course for each trainee.", "#7C3AED", "#C4B5FD"),
                    ("Digital skills lab", "WOMEN_EMPOWERMENT", "140000", "Ten laptops and a trainer for basic computer courses.", "#6D28D9", "#DDD6FE")]),
    dict(key="pashu", legal="Pashu Raksha Kendra Trust", display="Pashu Raksha", type="PUBLIC_TRUST", city="Ahmedabad",
         district="Ahmedabad", state="Gujarat", pin="380015", causes=["ANIMAL_WELFARE"], g80=False,
         pan="", darpan="GJ/2019/0600606", color="#0369A1",
         desc="Rescues injured street animals and runs a free treatment and vaccination shelter in Ahmedabad.",
         team=[("Hetal", "Patel", "PRIMARY_ADMIN"), ("Kunal", "Shah", "SECONDARY_ADMIN"), ("Bhavin", "Desai", "STAFF")],
         campaigns=[("Ambulance for injured animals", "ANIMAL_WELFARE", "350000", "A second rescue van to reach animals across the city.", "#0369A1", "#7DD3FC"),
                    ("Rabies vaccination drive", "ANIMAL_WELFARE", "50000", "Vaccines for 1,000 street dogs before the monsoon.", "#0F766E", "#5EEAD4")]),
]
FIRST = ["Aarav", "Vivaan", "Aditya", "Ishaan", "Kabir", "Reyansh", "Ananya", "Diya", "Saanvi", "Aadhya", "Myra",
         "Kiara", "Rahul", "Amit", "Suresh", "Ramesh", "Deepak", "Nikhil", "Priyanka", "Shreya", "Pallavi", "Swati",
         "Gaurav", "Harsh", "Tanvi", "Ritu", "Farhan", "Zoya", "Joseph", "Maria", "Gurpreet", "Simran", "Arvind",
         "Lata", "Mohan", "Usha"]
LAST = ["Sharma", "Iyer", "Nair", "Reddy", "Gupta", "Mehta", "Banerjee", "Das", "Kapoor", "Malhotra", "Pillai",
        "Menon", "Jain", "Saxena", "Mishra", "Pandey", "Kulkarni", "Gokhale", "Chavan", "Naik", "Bose", "Sen",
        "Arora", "Bhatia", "Rao", "Qureshi", "Khan", "Dsouza", "Fernandes", "Gill", "Kaur", "Rajan", "Murthy",
        "Thakur", "Lal", "Devi"]
LANGS = ["en", "hi", "mr"]


def slug(first, last):
    return f"{first}.{last}".lower()


# ---------------------------------------------------------------- keycloak
def kc_admin():
    r = http("POST", KC + "/realms/master/protocol/openid-connect/token",
             form=dict(grant_type="password", client_id="admin-cli", username=E["KEYCLOAK_ADMIN_USER"],
                       password=E["KEYCLOAK_ADMIN_PASSWORD"]))
    return r["access_token"]


ADM = None


def kca(method, path, body=None):
    global ADM
    if ADM is None:
        ADM = kc_admin()
    try:
        return http(method, KC + "/admin/realms/ngo" + path, ADM, body)
    except ApiError as e:
        if e.status != 401:
            raise
        ADM = kc_admin()
        return http(method, KC + "/admin/realms/ngo" + path, ADM, body)


def ensure_cli_client():
    found = kca("GET", "/clients?clientId=ngo-admin-cli")
    if not found:
        kca("POST", "/clients", dict(clientId="ngo-admin-cli", name="Temporary demo seeding (removed after run)",
                                     enabled=True, publicClient=True, standardFlowEnabled=False,
                                     directAccessGrantsEnabled=True))
        log("keycloak: temporary client ngo-admin-cli created")


def remove_cli_client():
    for c in kca("GET", "/clients?clientId=ngo-admin-cli") or []:
        kca("DELETE", "/clients/" + c["id"])
        log("keycloak: temporary client ngo-admin-cli removed")


def ensure_user(email, first, last, pw, roles=()):
    u = kca("GET", "/users?exact=true&email=" + urllib.parse.quote(email))
    if not u:
        kca("POST", "/users", dict(username=email, email=email, firstName=first, lastName=last, enabled=True,
                                   emailVerified=True, requiredActions=[]))
        u = kca("GET", "/users?exact=true&email=" + urllib.parse.quote(email))
    uid = u[0]["id"]
    kca("PUT", f"/users/{uid}", dict(firstName=first, lastName=last, enabled=True, emailVerified=True, requiredActions=[]))
    kca("PUT", f"/users/{uid}/reset-password", dict(type="password", value=pw, temporary=False))
    want = ["offline_access"] + list(roles)
    reps = [kca("GET", "/roles/" + r) for r in want]
    kca("POST", f"/users/{uid}/role-mappings/realm", reps)
    return uid


TOKENS = {}


def token(email, pw):
    t = TOKENS.get(email)
    if t and t[1] > time.time() + 30:
        return t[0]
    r = http("POST", KC + "/realms/ngo/protocol/openid-connect/token",
             form=dict(grant_type="password", client_id="ngo-admin-cli", username=email, password=pw, scope="openid"))
    TOKENS[email] = (r["access_token"], time.time() + r.get("expires_in", 300))
    return r["access_token"]


def person(email, first, last, pw, lang="en", gender="UNDISCLOSED", roles=(), phone=None):
    ensure_user(email, first, last, pw, roles)
    tok = token(email, pw)
    me = api("GET", "/api/v1/me", tok)
    if not me.get("profileComplete"):
        try:
            api("PUT", "/api/v1/me/profile", tok, dict(displayName=f"{first} {last}", preferredLanguage=lang,
                                                        birthDate=f"19{random.randint(60, 99)}-0{random.randint(1, 9)}-1{random.randint(0, 9)}",
                                                        gender=gender, confirmAdult=True))
        except ApiError as e:
            if e.code != "BIRTH_DATE_LOCKED":
                raise
    if phone and not me.get("phoneVerified"):
        api("POST", "/api/v1/me/phone/otp", tok, dict(phone=phone))
        code = None
        for _ in range(15):
            time.sleep(2)
            q = urllib.parse.quote(f"to:+91{phone}@sms.local")
            msgs = (http("GET", f"{MAILPIT}/api/v1/search?query={q}") or {}).get("messages", [])
            if msgs:
                text = http("GET", f"{MAILPIT}/api/v1/message/{msgs[0]['ID']}").get("Text", "")
                import re
                m = re.search(r"\b(\d{6})\b", text)
                if m:
                    code = m.group(1)
                    break
        if not code:
            raise RuntimeError(f"no OTP for {phone} in the test mailbox")
        api("POST", "/api/v1/me/phone/verify", tok, dict(phone=phone, code=code))
        log(f"phone verified: {email}")
    return tok


# ---------------------------------------------------------------- run
def main():
    state = json.load(open(STATE)) if os.path.exists(STATE) else {}
    if E.get("PAYMENTS_MOCK_ENABLED", "false") != "true":
        log("WARNING: PAYMENTS_MOCK_ENABLED is not true - online test donations will be skipped")
    ensure_cli_client()
    creds = []
    results = {"pass": 0, "fail": 0}

    def check(name, ok, detail=""):
        results["pass" if ok else "fail"] += 1
        log(("PASS " if ok else "FAIL ") + name + ("" if ok else "  " + str(detail)[:300]))

    try:
        # platform admin
        pa_email = "platform.admin@yogdaan.test"
        pa = person(pa_email, "Platform", "Admin", PW_ADMIN, roles=["PLATFORM_ADMIN"])
        creds.append(("PLATFORM_ADMIN", "Yogdaan platform", "Platform Admin", pa_email, PW_ADMIN))

        ngo_ids = {}
        for n in NGOS:
            dom = n["key"] + ".test"
            team = []
            for i, (f, l, role) in enumerate(n["team"]):
                email = f"{slug(f, l)}@{dom}"
                tok = person(email, f, l, PW_TEAM, LANGS[i % 3], "FEMALE" if f[-1] in "aiy" else "MALE",
                             phone=f"91000{NGOS.index(n)}{i:04d}")
                team.append(dict(email=email, role=role, first=f, last=l))
                creds.append((role, n["display"], f"{f} {l}", email, PW_TEAM))
            admin = team[0]
            atok = token(admin["email"], PW_TEAM)
            mine = api("GET", "/api/v1/ngos/mine", atok) or []
            mine = mine if isinstance(mine, list) else mine.get("items", [])
            ngo = next((x for x in mine if x.get("displayName") == n["display"]), None)
            if not ngo:
                sec = team[1]
                ngo = api("POST", "/api/v1/ngos", atok, dict(
                    organization=dict(legalName=n["legal"], displayName=n["display"], registrationType=n["type"],
                                      description=n["desc"], areasOfActivity=n["causes"],
                                      officialEmail=f"contact@{dom}", officialPhone=f"98{random.randint(10000000, 99999999)}",
                                      website=""),
                    secondaryAdmin=dict(name=f"{sec['first']} {sec['last']}", email=sec["email"], phone="", designation="Secretary")))
                log(f"ngo: draft {n['display']} {ngo['id']}")
            nid = ngo["id"]
            ngo_ids[n["key"]] = nid
            base = f"/api/v1/ngos/{nid}"
            if ngo.get("verificationStatus") in ("NOT_SUBMITTED", "INFO_REQUESTED", None):
                api("PUT", base + "/registration", atok, dict(
                    registrationNumber=f"{n['state'][:2].upper()}/{random.randint(1000, 9999)}/{n['key'].upper()}",
                    registrationDate=f"20{random.randint(10, 19)}-06-15", registrationState=n["state"],
                    darpanId=n["darpan"], pan=n["pan"] or None,
                    tax80gNumber=(f"{n['pan']}F20214" if n["g80"] else None),
                    tax80gValidUntil=("2031-03-31" if n["g80"] else None)))
                api("PUT", base + "/address", atok, dict(line1=f"{random.randint(2, 99)}, Seva Bhavan, Main Road", line2="",
                                                        city=n["city"], district=n["district"], state=n["state"], pinCode=n["pin"]))
                api("PUT", base + "/contacts/SIGNATORY", atok, dict(name=f"{admin['first']} {admin['last']}", email=admin["email"],
                                                                   phone="", designation="President"))
                docs = ["REGISTRATION_CERTIFICATE", "ADDRESS_PROOF", "SIGNATORY_ID", "TRUST_DEED_OR_MOA"]
                if n["pan"]:
                    docs.append("PAN_CARD")
                if n["g80"]:
                    docs += ["CERTIFICATE_80G", "CERTIFICATE_12A"]
                dl = api("GET", base + "/documents", atok) or []
                dl = dl if isinstance(dl, list) else dl.get("items", [])
                have = {d["type"] for d in dl if d.get("status") != "SUPERSEDED"}
                for d in docs:
                    if d not in have:
                        multipart(base + "/documents", atok, {"type": d}, d.lower() + ".pdf", pdf(f"{n['legal']} {d}"), "application/pdf")
                multipart(base + "/documents", atok, {"type": "LOGO"}, "logo.png", png(n["color"], "#FFFFFF", 256, 256), "image/png")
                ngo = api("GET", base, atok)
                check(f"{n['display']}: registration complete", not ngo.get("missing") and not ngo.get("missingForSubmission"),
                      json.dumps({k: v for k, v in ngo.items() if "issing" in k}))
                ngo = api("POST", base + "/submit", atok)
                log(f"ngo: submitted {n['display']}")
            ngo = api("GET", base, atok)
            if ngo["verificationStatus"] == "PENDING":
                api("POST", f"/api/v1/admin/ngos/{nid}/verification", pa, dict(
                    decision="APPROVE", messageToNgo="Welcome to Yogdaan (test server).", confirm80g=n["g80"]))
                log(f"ngo: approved {n['display']}")
            ngo = api("GET", base, atok)
            check(f"{n['display']}: approved + public", ngo["verificationStatus"] == "APPROVED" and ngo["publicProfileStatus"] == "PUBLISHED",
                  ngo["verificationStatus"])
            check(f"{n['display']}: 80G verified = {n['g80']}", bool(ngo.get("tax80gVerified")) == n["g80"], ngo.get("tax80gVerified"))

            # team (wait for subscription + membership events)
            staff, last = None, None
            for _ in range(30):
                try:
                    staff = api("GET", base + "/staff", token(admin["email"], PW_TEAM))
                    break
                except ApiError as e:
                    last = e
                    time.sleep(2)
            if staff is None:
                raise RuntimeError(f"staff list for {n['display']} not available: {last}")
            atok = token(admin["email"], PW_TEAM)
            staff = staff if isinstance(staff, list) else staff.get("items", [])
            invs = api("GET", base + "/staff/invitations", atok) or []
            invs = invs if isinstance(invs, list) else invs.get("items", [])
            for m in team[1:]:
                if any(s.get("email") == m["email"] and s.get("status") == "ACTIVE" for s in staff):
                    continue
                inv = next((i for i in invs if i.get("email") == m["email"]), None)
                if not inv:
                    inv = api("POST", base + "/staff/invitations", atok, dict(email=m["email"], role=m["role"]))
                api("POST", f"/api/v1/me/invitations/{inv['id']}/accept", token(m["email"], PW_TEAM))
                log(f"team: {m['email']} joined as {m['role']}")
            staff = api("GET", base + "/staff", atok)
            staff = staff if isinstance(staff, list) else staff.get("items", [])
            check(f"{n['display']}: team of {len(team)} active", sum(1 for s in staff if s.get("status") == "ACTIVE") >= len(team),
                  [(s.get("email"), s.get("role"), s.get("status")) for s in staff])

            # payments: TEST provider (no real money)
            if E.get("PAYMENTS_MOCK_ENABLED") == "true":
                try:
                    ps = api("GET", base + "/payment-settings", atok)
                except ApiError:
                    ps = {}
                if (ps or {}).get("status") != "ACTIVE":
                    ps = api("PUT", base + "/payment-settings", atok, dict(provider="MOCK", merchantReference="mock_" + n["key"],
                                                                         guestCheckoutAllowed=True, minAmount="10", maxAmount="1000000",
                                                                         receiptPrefix=n["key"][:4].upper()))
                check(f"{n['display']}: test payments active", ps.get("status") == "ACTIVE", ps)

            # campaigns: first by staff (submit -> admin approves), second by admin (approve = publish)
            cbase = base + "/campaigns"
            existing = (api("GET", cbase + "?size=100", atok) or {}).get("items", [])
            stok = token(team[2]["email"], PW_TEAM)
            for ci, (title, cause, goal, summary, c1, c2) in enumerate(n["campaigns"]):
                c = next((x for x in existing if x["title"] == title), None)
                by = stok if ci == 0 else atok
                if not c:
                    c = api("POST", cbase, by, dict(title=title, cause=cause, goalAmount=goal, startDate=day(-10), endDate=day(60 + ci * 30),
                                                    summary=summary, story=summary + "\n\nThis is a demo campaign on the Yogdaan test server. "
                                                    "No real money is collected."))
                    multipart(f"{cbase}/{c['id']}/image", by, {}, "cover.png", png(c1, c2), "image/png")
                    if ci == 0:
                        c = api("POST", f"{cbase}/{c['id']}/submit", by)
                if c["status"] not in ("LIVE", "ACTIVE", "PUBLISHED"):
                    c = api("GET", f"{cbase}/{c['id']}", atok)
                    c = api("POST", f"{cbase}/{c['id']}/approve", atok, dict(version=c["version"]))
                check(f"{n['display']}: campaign '{title}' live", c["status"] in ("LIVE", "ACTIVE", "PUBLISHED"), c["status"])
                n.setdefault("cids", []).append(c["id"])
            n["team_tokens"] = team

        # donors
        donors = []
        for i in range(36):
            f, l = FIRST[i], LAST[i]
            email = f"{slug(f, l)}@donor.test"
            person(email, f, l, PW_DONOR, LANGS[i % 3], "FEMALE" if f[-1] in "aiy" else "MALE")
            donors.append(dict(email=email, name=f"{f} {l}", pan=f"{'ABCDEFGHJK'[i % 10]}{'PQRST'[i % 5]}{'XYZWV'[i % 5]}P{l[0]}{1000 + i * 37}{'ABCDEFGHJ'[i % 9]}"))
            creds.append(("DONOR", "-", f"{f} {l}", email, PW_DONOR))
        log(f"donors: {len(donors)} ready")

        # donations (once)
        if not state.get("donations_done"):
            online = offline = 0
            if E.get("PAYMENTS_MOCK_ENABLED") == "true":
                for i, d in enumerate(donors):
                    dtok = token(d["email"], PW_DONOR)
                    for k in range(1 + (i % 3)):
                        n = NGOS[(i + k * 2) % len(NGOS)]
                        cid = n["cids"][(i + k) % 2] if (i + k) % 5 else None
                        amt = random.choice(["251", "500", "1001", "1500", "2100", "5000", "11000"])
                        g80 = n["g80"] and (i % 2 == 0)
                        body = dict(ngoId=ngo_ids[n["key"]], amount=amt, donorName=d["name"], donorEmail=d["email"], wants80g=g80)
                        if cid:
                            body["campaignId"] = cid
                        else:
                            body["purpose"] = "General donation"
                        if g80:
                            body.update(donorPan=d["pan"], donorAddress=f"{10 + i}, Shanti Nagar, {n['city']} {n['pin']}")
                        try:
                            co = http("POST", API + "/api/v1/donations", dtok, body, headers={"Idempotency-Key": str(uuid.uuid4())})
                            paid = api("POST", f"/api/v1/dev/mock-payments/{co['orderId']}/complete", dtok, dict(scenario="SUCCESS"))
                            online += 1
                        except ApiError as e:
                            log(f"online donation failed for {d['email']}: {e}")
                check("online test donations", online >= 60, online)
            for n in NGOS:
                team = n["team_tokens"]
                for j, (who, chan, amt, nm) in enumerate([(team[2], "CASH", "1500.00", "Shanta Kale"),
                                                         (team[-1], "CHEQUE", "10000.00", f"{n['city']} Traders LLP"),
                                                         (team[2], "UPI_DIRECT", "750.00", "Ganesh Pawar")]):
                    body = dict(donorName=nm, amount=amt, channel=chan, wants80g=n["g80"], reference=f"DEMO-{n['key'].upper()}-{j + 1}",
                                receivedOn=day(-j), purpose="General donation")
                    if n["g80"]:
                        body["donorPan"] = ["ABCPK1234L", "AAJFP1234K", "BGHPP4321Q"][j]
                        body["donorAddress"] = f"{j + 3}, Station Road, {n['city']}"
                    try:
                        api("POST", f"/api/v1/ngos/{ngo_ids[n['key']]}/donations/offline", token(who["email"], PW_TEAM), body)
                        offline += 1
                    except ApiError as e:
                        if e.status == 400 and "donorAddress" in e.body:
                            body.pop("donorAddress", None)
                            api("POST", f"/api/v1/ngos/{ngo_ids[n['key']]}/donations/offline", token(who["email"], PW_TEAM), body)
                            offline += 1
                        else:
                            log(f"offline donation failed ({n['display']} {chan}): {e}")
            check("offline donations recorded", offline == 3 * len(NGOS), offline)
            state["donations_done"] = True
            state["online"], state["offline"] = online, offline
            json.dump(state, open(STATE, "w"))

        # verification
        time.sleep(20)
        pub = api("GET", "/api/v1/public/ngos?size=50", None)
        items = pub if isinstance(pub, list) else pub.get("items", pub.get("content", []))
        check("public NGO list shows the 6 demo NGOs", sum(1 for x in items if x.get("displayName") in [n["display"] for n in NGOS]) == 6, len(items))
        total = 0
        for n in NGOS:
            for cid in n["cids"]:
                c = api("GET", f"/api/v1/public/campaigns/{cid}", None)
                total += float(c.get("raisedAmount") or 0)
        check("campaigns show money raised", total > 0, total)
        d0 = donors[0]
        mine = api("GET", "/api/v1/donations/mine", token(d0["email"], PW_DONOR))
        mi = mine.get("items", []) if isinstance(mine, dict) else mine
        check(f"donor {d0['name']} sees donation history", len(mi) >= 1 and all(x["status"] == "PAID" for x in mi), [x.get("status") for x in mi])
        for n in NGOS[:2]:
            atok = token(n["team_tokens"][0]["email"], PW_TEAM)
            ds = api("GET", f"/api/v1/ngos/{ngo_ids[n['key']]}/donations?size=100", atok)
            di = ds.get("items", []) if isinstance(ds, dict) else ds
            check(f"{n['display']}: admin sees donations", len(di) >= 3, len(di))
            try:
                rc = api("GET", f"/api/v1/ngos/{ngo_ids[n['key']]}/receipts?size=100", atok)
                ri = rc.get("items", []) if isinstance(rc, dict) else rc
                check(f"{n['display']}: receipts issued", len(ri) >= 1, len(ri))
            except ApiError as e:
                check(f"{n['display']}: receipts issued", False, e)
    finally:
        try:
            remove_cli_client()
        except Exception as e:
            log("could not remove temporary client:", e)
        with open(CREDS, "w") as fh:
            fh.write("YOGDAAN TEST SERVER - DEMO ACCOUNTS (fictional people; test server only)\n")
            fh.write("Sign in: https://auth.130-210-11-54.sslip.io  (the app uses it automatically)\n\n")
            for c in creds:
                fh.write("{:<16} {:<18} {:<22} {:<40} {}\n".format(*c))
        os.chmod(CREDS, 0o600)
    log(f"RESULT pass={results['pass']} fail={results['fail']}")


if __name__ == "__main__":
    main()
