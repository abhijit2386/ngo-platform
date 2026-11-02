# Yogdaan on OCI: one server (Oracle Linux 8/9, x86_64)

Everything runs on one machine (8 OCPU / 32 GB planned): PostgreSQL, Keycloak, Caddy (HTTPS), the Eureka discovery
server, 11 Yogdaan services and the gateway as systemd services, and Elasticsearch + Kibana + Filebeat for logs. Files
(receipts, bills, vault papers) and database backups go to OCI Object Storage. No containers (owner decision).
Only ports 22, 80 and 443 answer from outside, even if every OCI port is open: all other programs listen on
127.0.0.1, and firewalld allows ssh/http/https only.

## 1. Before the first install

**OCI console**
- VM: Oracle Linux 8 or 9, x86_64. Optional data block volume: mount it at `/var/lib/pgsql` before step `postgres`.
- Reserved public IP. Ingress: 443 and 80 from anywhere, 22 from your IP (OCI security list).
- Object Storage: private buckets `yogdaan-files` and `yogdaan-backups` in the compartment set under
  *Tenancy details > Object Storage settings > Amazon S3 Compatibility API designated compartment*.
- Your profile > *Customer Secret Keys* > generate: access key + secret key (S3_ACCESS_KEY / S3_SECRET_KEY).
- Namespace (Tenancy details) and region code (e.g. `ap-mumbai-1`):
  `S3_ENDPOINT=https://<namespace>.compat.objectstorage.ap-mumbai-1.oraclecloud.com`
- E-mail: Email Delivery approved sender `no-reply@yogdaan.co.in`, SMTP credentials, SPF/DKIM records in DNS.

**DNS (domain registrar)**: A records `yogdaan.co.in`, `www.yogdaan.co.in`, `api.yogdaan.co.in`,
`auth.yogdaan.co.in` -> the reserved IP (TTL 300 while testing).

**Laptop**
- SSH key: `ssh-keygen -t ed25519 -f %USERPROFILE%\.ssh\yogdaan_oci` ; add `yogdaan_oci.pub` to the VM (user `opc`).
- `copy config\yogdaan.env.prod.example config\yogdaan.env.prod` and fill the REQUIRED lines (never commit it,
  never paste keys in chat). Passwords and secrets left empty are generated on the server.
- `scripts\build-backend.cmd` (the deploy sends the jars built and tested on the laptop).

## 2. Install (from the laptop, through the runner)

```
scripts\deploy-cloud.ps1 -Server <ip> -Steps all
```
or one component at a time (each step can be re-run; it stops with a clear message):

| Step | What it does |
|---|---|
| `config` | `/etc/yogdaan/yogdaan.env` (0640 root:yogdaan); keeps values already on the server; generates empty secrets |
| `base` | OS check, OpenJDK 21, fonts for PDFs (rupee sign, Devanagari), firewalld (ssh/http/https), user `yogdaan`, IST timezone |
| `postgres` | PostgreSQL 17 (PGDG), localhost only, 300 connections, one role + database per service and `keycloak` |
| `keycloak` | Keycloak 26.7.2 production mode on 127.0.0.1:8180; realm imported once without test users, without the password-grant client `ngo-admin-cli`, HTTPS required, fresh client secret, real SMTP |
| `caddy` | HTTPS certificates (Let's Encrypt) for the domain, `api.` and `auth.`; blocks `/internal`, `/actuator`, Keycloak `/admin` |
| `services` | systemd units `yogdaan@<service>` (discovery first, gateway last), 127.0.0.1 only, memory limits, ECS JSON log files in `/var/log/yogdaan` |
| `elk` | Elasticsearch, Kibana, Filebeat 9.x on localhost; logs kept 30 days |
| `backup` | nightly 02:30 `pg_dumpall` -> `yogdaan-backups/db/` (30 days), runs once immediately |
| `smoke` | services healthy, HTTPS + issuer, blocked paths, bucket write/read, fonts, logs in Elasticsearch, open ports |

## 3. Daily use

- Update services: `scripts\build-backend.cmd`, then `scripts\deploy-cloud.ps1 -Server <ip> -Steps services smoke`
  (or `-Only receipt-service,reporting-service`). Rollback: `-Steps services -Rollback`.
- Logs: `ssh -i %USERPROFILE%\.ssh\yogdaan_oci -L 5601:127.0.0.1:5601 opc@<ip>` then http://localhost:5601
  (user `elastic`, password: `sudo cat /etc/yogdaan/secrets/elastic-password`); data view `logs-yogdaan-*`.
  On the server: `journalctl -u yogdaan@receipt-service -n 100`, `tail -f /var/log/yogdaan/receipt-service.log`.
- Keycloak admin: `ssh ... -L 8180:127.0.0.1:8180 opc@<ip>` then http://localhost:8180/admin (user `yogdaan-admin`,
  password `KEYCLOAK_ADMIN_PASSWORD` in `/etc/yogdaan/yogdaan.env`). Keycloak calls this first admin "temporary":
  create a permanent admin account in the master realm and remove the temporary one.
- Database: `ssh ... -L 5432:127.0.0.1:5432 opc@<ip>` (roles = service names, password `DB_PASSWORD`).
- Restore a backup: `rclone copy oci:yogdaan-backups/db/<file> /tmp/`, stop the services
  (`systemctl stop 'yogdaan@*' keycloak`), `gunzip -c /tmp/<file> | sudo -u postgres psql`, start them again.

## 4. Known limits (2 Nov 2026)

- Online payments: only the mock gateway exists; it is OFF in production (it would create real-looking paid
  donations and 80G receipts). A real gateway is a separate feature.
- Phone OTP: no SMS provider yet (`SMS_PROVIDER=dev-mailbox`).
- Google / Facebook sign-in: after the HTTPS domain is live (they also need privacy-policy and data-deletion pages).
- The S3 path of the services (OCI Object Storage) is first exercised on the server: the smoke test writes and reads
  the bucket with rclone; the first document upload in the app confirms the services' own client.
- Filebeat writes with the `elastic` user; replace it with an API key with write rights only.
- The kit was checked with `bash -n` and ShellCheck; it could not be run on Oracle Linux before the server existed.
