# Android test builds

Test APKs for installing directly on a phone (Settings > allow "install unknown apps" for the browser or file app).
Only 64-bit ARM (`arm64-v8a`, practically every Android phone since about 2019), to stay under GitHub's 100 MB file
limit. Profile builds: fast, but signed with the debug key - not for the Play Store.

| File | Built | Server | Notes |
|---|---|---|---|
| `yogdaan-cloud-test-arm64.apk` | 3 Nov 2026 | OCI test4: `https://api.130-210-11-54.sslip.io` (API), `https://auth.130-210-11-54.sslip.io` (sign-in) | TEST MODE: HTTPS on sslip.io names (no DNS needed), no test payments; e-mails and OTPs in the test mailbox `https://mail.130-210-11-54.sslip.io` |

Rebuild: `scripts\run-mobile.ps1 -Mode profile -Cloud -ApiUrl https://api.<ip-dashes>.sslip.io -AuthUrl https://auth.<ip-dashes>.sslip.io/realms/ngo`
for the emulator, or the arm64 command in IMPLEMENTATION_STATUS.md (3 Nov 2026) for this folder. Once the domain has
HTTPS, builds use `https://api.yogdaan.co.in` and this sslip.io build is retired.
