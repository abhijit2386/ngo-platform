#!/usr/bin/env bash
# Keycloak in production mode on 127.0.0.1:8180 behind Caddy (https://AUTH_HOST). The realm is imported once from
# files/ngo-realm.json, cleaned for production: no test users, no password-grant script client, HTTPS required,
# a fresh identity-admin secret and the real SMTP server. Later runs keep the realm (changes go through the console).
source "$(dirname "$0")/../lib.sh"
load_env
require_env AUTH_HOST KEYCLOAK_DB_PASSWORD KEYCLOAK_ADMIN_USER KEYCLOAK_ADMIN_PASSWORD KEYCLOAK_ADMIN_CLIENT_SECRET
KC_HOME="/opt/keycloak-${KEYCLOAK_VERSION}"
id keycloak >/dev/null 2>&1 || useradd --system --home-dir /opt/keycloak --shell /sbin/nologin keycloak

if [ ! -x "$KC_HOME/bin/kc.sh" ]; then
  log "download Keycloak ${KEYCLOAK_VERSION}"
  tmp=$(mktemp -d)
  curl -fsSL -o "$tmp/kc.tar.gz" "https://github.com/keycloak/keycloak/releases/download/${KEYCLOAK_VERSION}/keycloak-${KEYCLOAK_VERSION}.tar.gz"
  tar -xzf "$tmp/kc.tar.gz" -C /opt
  rm -rf "$tmp"
fi
ln -sfn "$KC_HOME" /opt/keycloak
# Yogdaan sign-in look (infrastructure/keycloak/themes): copied on every run, Keycloak restarted below
if [ -d "$KIT_DIR/files/themes" ]; then cp -r "$KIT_DIR/files/themes/." "$KC_HOME/themes/"; ok "themes: $(ls "$KIT_DIR/files/themes" | tr '\n' ' ')"; fi
chown -R keycloak:keycloak "$KC_HOME"

log "configuration"
cat > "$KC_HOME/conf/keycloak.conf" <<CONF
db=postgres
db-url=jdbc:postgresql://127.0.0.1:5432/keycloak
db-username=keycloak
db-password=${KEYCLOAK_DB_PASSWORD}
hostname=https://${AUTH_HOST}
hostname-admin=http://localhost:8180
hostname-backchannel-dynamic=true
http-enabled=true
http-host=127.0.0.1
http-port=8180
proxy-headers=xforwarded
health-enabled=true
http-management-port=9000
CONF
chmod 0600 "$KC_HOME/conf/keycloak.conf"; chown keycloak:keycloak "$KC_HOME/conf/keycloak.conf"
sudo -u keycloak JAVA_HOME=/opt/java21 "$KC_HOME/bin/kc.sh" build --db=postgres --health-enabled=true >/dev/null
ok "built for PostgreSQL"

MARK=/etc/yogdaan/.keycloak-realm-imported
if [ ! -f "$MARK" ]; then
  log "production realm (once)"
  [ -f "$KIT_DIR/files/ngo-realm.json" ] || die "files/ngo-realm.json missing in the kit"
  jq --arg secret "$KEYCLOAK_ADMIN_CLIENT_SECRET" --arg host "${MAIL_HOST:-}" --arg port "${MAIL_PORT:-587}" \
     --arg user "${MAIL_USER:-}" --arg pw "${MAIL_PASSWORD:-}" --arg from "${MAIL_FROM:-no-reply@yogdaan.co.in}" \
     --arg name "${MAIL_FROM_NAME:-Yogdaan}" '
    del(.users)
    | .sslRequired = "external"
    | .clients |= map(select(.clientId != "ngo-admin-cli"))
    | .clients |= map(if .clientId == "ngo-identity-admin" then .secret = $secret else . end)
    | if $host == "" then del(.smtpServer) else .smtpServer = {host: $host, port: $port, from: $from, fromDisplayName: $name, starttls: "true", ssl: "false",
                     auth: (if $user == "" then "false" else "true" end), user: $user, password: $pw} end' \
     "$KIT_DIR/files/ngo-realm.json" > /tmp/ngo-realm-prod.json
  chown keycloak:keycloak /tmp/ngo-realm-prod.json; chmod 0600 /tmp/ngo-realm-prod.json
  sudo -u keycloak JAVA_HOME=/opt/java21 "$KC_HOME/bin/kc.sh" import --optimized --file /tmp/ngo-realm-prod.json --override false >/dev/null
  rm -f /tmp/ngo-realm-prod.json
  touch "$MARK"
  ok "realm 'ngo' imported (no test users)"
fi

cat > /etc/systemd/system/keycloak.service <<UNIT
[Unit]
Description=Keycloak (Yogdaan sign-in)
After=network-online.target postgresql-${PG_MAJOR}.service
Wants=network-online.target
Requires=postgresql-${PG_MAJOR}.service

[Service]
User=keycloak
Environment=JAVA_HOME=/opt/java21
Environment="JAVA_OPTS_KC_HEAP=-Xms256m -Xmx768m"
ExecStart=${KC_HOME}/bin/kc.sh start --optimized
Restart=on-failure
RestartSec=10
SuccessExitStatus=143
LimitNOFILE=65536
MemoryMax=1536M

[Install]
WantedBy=multi-user.target
UNIT
systemctl daemon-reload
systemctl enable keycloak >/dev/null
# Admin account for the master realm: the import above already created the master realm, so Keycloak's
# "admin on first start" never happens; bootstrap-admin creates it (server stopped, once).
ADMIN_MARK=/etc/yogdaan/.keycloak-admin-created
if [ ! -f "$ADMIN_MARK" ]; then
  systemctl stop keycloak 2>/dev/null || true
  sudo -u keycloak env JAVA_HOME=/opt/java21 KC_ADMIN_U="$KEYCLOAK_ADMIN_USER" KC_ADMIN_P="$KEYCLOAK_ADMIN_PASSWORD" \
    "$KC_HOME/bin/kc.sh" bootstrap-admin user --optimized --username:env KC_ADMIN_U --password:env KC_ADMIN_P >/dev/null
  touch "$ADMIN_MARK"
  ok "master admin $KEYCLOAK_ADMIN_USER created"
fi
systemctl restart keycloak
wait_http http://127.0.0.1:9000/health/ready 180 || die "Keycloak did not become ready (journalctl -u keycloak)"
bash "$KIT_DIR/kc-required-actions.sh"
ok "Keycloak ready; admin console only through SSH: ssh -L 8180:127.0.0.1:8180 ... then http://localhost:8180/admin"
ok "admin user: $KEYCLOAK_ADMIN_USER (password: KEYCLOAK_ADMIN_PASSWORD in $ENV_FILE)"
