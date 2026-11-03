#!/usr/bin/env bash
# PostgreSQL (PGDG packages), listening on localhost only, one role + database per service and one for Keycloak.
# Data: /var/lib/pgsql/<version>/data (mount the data block volume at /var/lib/pgsql BEFORE this step if you use one).
source "$(dirname "$0")/../lib.sh"
load_env
require_env DB_PASSWORD KEYCLOAK_DB_PASSWORD
OSV=$(os_major)
SVC="postgresql-${PG_MAJOR}"
PGBIN="/usr/pgsql-${PG_MAJOR}/bin"
PGDATA="/var/lib/pgsql/${PG_MAJOR}/data"

if ! rpm -q "postgresql${PG_MAJOR}-server" >/dev/null 2>&1; then
  log "install PostgreSQL ${PG_MAJOR}"
  rpm -q pgdg-redhat-repo >/dev/null 2>&1 || \
    dnf -y -q install "https://download.postgresql.org/pub/repos/yum/reporpms/EL-${OSV}-x86_64/pgdg-redhat-repo-latest.noarch.rpm" >/dev/null
  # PGDG's repo file uses $releasever_major.$releasever_minor; Oracle Linux has no minor value, which gives
  # ".../rhel-9.-x86_64" (404). Their rhel-<major>-<arch> folders are the same packages.
  sed -i 's/rhel-\$releasever_major\.\$releasever_minor-/rhel-\$releasever_major-/g' /etc/yum.repos.d/pgdg-redhat-all.repo
  [ -s /etc/dnf/vars/releasever_major ] || echo "$OSV" > /etc/dnf/vars/releasever_major
  dnf -qy module disable postgresql >/dev/null 2>&1 || true   # use PGDG, not the distribution's module
  dnf_install "postgresql${PG_MAJOR}-server" "postgresql${PG_MAJOR}"
fi
# contrib: extensions the services create themselves (document-service: pg_trgm for vault search)
rpm -q "postgresql${PG_MAJOR}-contrib" >/dev/null 2>&1 || dnf_install "postgresql${PG_MAJOR}-contrib"
if [ ! -f "$PGDATA/PG_VERSION" ]; then
  PGSETUP_INITDB_OPTIONS="--auth-local=peer --auth-host=scram-sha-256 --encoding=UTF8" \
    "$PGBIN/postgresql-${PG_MAJOR}-setup" initdb >/dev/null
  ok "database cluster created"
fi

log "settings: localhost only, connections for 12 services + Keycloak"
cat > "$PGDATA/conf.d.yogdaan.conf" <<CONF
listen_addresses = 'localhost'
max_connections = 300
shared_buffers = 2GB
effective_cache_size = 8GB
work_mem = 16MB
maintenance_work_mem = 256MB
password_encryption = 'scram-sha-256'
timezone = 'UTC'
log_min_duration_statement = 2000
CONF
grep -q "conf.d.yogdaan.conf" "$PGDATA/postgresql.conf" || echo "include_if_exists = 'conf.d.yogdaan.conf'" >> "$PGDATA/postgresql.conf"
cat > "$PGDATA/pg_hba.conf" <<HBA
# TYPE  DATABASE  USER  ADDRESS        METHOD
local   all       all                  peer
host    all       all   127.0.0.1/32   scram-sha-256
host    all       all   ::1/128        scram-sha-256
HBA
chown postgres:postgres "$PGDATA/conf.d.yogdaan.conf" "$PGDATA/pg_hba.conf"
systemctl enable "$SVC" >/dev/null
systemctl restart "$SVC"
ok "$(sudo -u postgres "$PGBIN/psql" -tAc 'select version()' | cut -c1-40)"

log "roles and databases"
role() { # name password
  sudo -u postgres "$PGBIN/psql" -q -v ON_ERROR_STOP=1 -v name="$1" -v pw="$2" <<'SQL'
SELECT format('CREATE ROLE %I LOGIN', :'name') WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'name') \gexec
SELECT format('ALTER ROLE %I LOGIN PASSWORD %L', :'name', :'pw') \gexec
SELECT format('CREATE DATABASE %I OWNER %I', :'name', :'name') WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = :'name') \gexec
SQL
}
for db in "${DATABASES[@]}"; do role "$db" "$DB_PASSWORD"; done
role keycloak "$KEYCLOAK_DB_PASSWORD"
ok "${#DATABASES[@]} service databases + keycloak (tables are created by each service on its first start)"
