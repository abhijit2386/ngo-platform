#!/usr/bin/env bash
# TEST MODE (owner, 3 Nov 2026): app testing by IP over plain HTTP before DNS / HTTPS / real e-mail exist.
#   :80   -> gateway (API)            /internal, /actuator blocked
#   :8081 -> Keycloak (sign-in)       /admin blocked
#   :8025 -> Mailpit (test mailbox: sign-up mails, phone OTPs), password protected
# Every e-mail goes to Mailpit, none reaches a real person. Undo: './install.sh caddy' (HTTPS) after DNS, real MAIL_*
# in the settings, and the realm's "Require SSL" back to "external requests" (TEST_MODE=off sudo ./test-mode.sh).
# Usage: sudo PUBLIC_IP=130.210.11.54 ./test-mode.sh
source "$(dirname "$0")/lib.sh"
need_root
load_env
IP="${PUBLIC_IP:?set PUBLIC_IP}"
KC_PORT=8081; MAIL_UI_PORT=8025

log "Mailpit (test mailbox) on 127.0.0.1:1025 (SMTP) and 127.0.0.1:8026 (web)"
if [ ! -x /usr/local/bin/mailpit ]; then
  tmp=$(mktemp -d)
  curl -fsSL -o "$tmp/mp.tgz" https://github.com/axllent/mailpit/releases/latest/download/mailpit-linux-amd64.tar.gz
  tar -xzf "$tmp/mp.tgz" -C "$tmp" mailpit
  install -m 0755 "$tmp/mailpit" /usr/local/bin/mailpit
  rm -rf "$tmp"
fi
id mailpit >/dev/null 2>&1 || useradd --system --home-dir /var/lib/mailpit --create-home --shell /sbin/nologin mailpit
cat > /etc/systemd/system/mailpit.service <<'UNIT'
[Unit]
Description=Mailpit test mailbox (TEST MODE)
After=network-online.target
[Service]
User=mailpit
ExecStart=/usr/local/bin/mailpit --smtp 127.0.0.1:1025 --listen 127.0.0.1:8026 --database /var/lib/mailpit/mailpit.db --max 5000
Restart=on-failure
[Install]
WantedBy=multi-user.target
UNIT
systemctl daemon-reload; systemctl enable --now mailpit >/dev/null; systemctl restart mailpit
wait_http http://127.0.0.1:8026/ 30 || die "Mailpit did not start (journalctl -u mailpit)"
ok "$(/usr/local/bin/mailpit version 2>/dev/null | head -1)"

log "settings: addresses by IP, e-mail to Mailpit"
set_env PUBLIC_API_BASE "http://$IP"; set_env PUBLIC_API_URL "http://$IP"; set_env PUBLIC_WEB_URL "http://$IP"
set_env KEYCLOAK_ISSUERS "http://$IP:$KC_PORT/realms/${KEYCLOAK_REALM:-ngo}"
set_env MAIL_HOST 127.0.0.1; set_env MAIL_PORT 1025; set_env MAIL_AUTH false; set_env MAIL_STARTTLS false
set_env MAIL_USER ""; set_env MAIL_PASSWORD ""
load_env

log "Caddy, plain HTTP on :80, :$KC_PORT, :$MAIL_UI_PORT"
if ! rpm -q caddy >/dev/null 2>&1; then
  dnf_install 'dnf-command(copr)'; dnf -y -q copr enable @caddy/caddy >/dev/null; dnf_install caddy
fi
setsebool -P httpd_can_network_connect 1 2>/dev/null || true
semanage port -a -t http_port_t -p tcp $KC_PORT 2>/dev/null || semanage port -m -t http_port_t -p tcp $KC_PORT 2>/dev/null || true
semanage port -a -t http_port_t -p tcp $MAIL_UI_PORT 2>/dev/null || semanage port -m -t http_port_t -p tcp $MAIL_UI_PORT 2>/dev/null || true
PWF="$SECRETS_DIR/mailpit-ui-password"
[ -s "$PWF" ] || { gen_secret | cut -c1-16 > "$PWF"; chmod 0600 "$PWF"; }
HASH=$(caddy hash-password --plaintext "$(cat "$PWF")")
cat > /etc/caddy/Caddyfile <<CADDY
{
	auto_https off
}

(logged) {
	log {
		output stderr
	}
}

http://:80 {
	import logged
	@internal path /internal/* /actuator /actuator/*
	respond @internal 404
	request_body {
		max_size 25MB
	}
	reverse_proxy 127.0.0.1:8080
}

http://:$KC_PORT {
	import logged
	@admin path /admin /admin/*
	respond @admin 404
	reverse_proxy 127.0.0.1:8180
}

http://:$MAIL_UI_PORT {
	import logged
	basic_auth {
		test $HASH
	}
	reverse_proxy 127.0.0.1:8026
}
CADDY
caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile >/dev/null 2>&1 || die "Caddyfile invalid"
systemctl enable caddy >/dev/null; systemctl restart caddy
firewall-cmd -q --permanent --add-port=$KC_PORT/tcp; firewall-cmd -q --permanent --add-port=$MAIL_UI_PORT/tcp; firewall-cmd -q --reload
ok "firewall: $(firewall-cmd --list-services) ports $(firewall-cmd --list-ports)"

log "Keycloak answers as http://$IP:$KC_PORT; realm accepts HTTP; realm e-mail -> Mailpit"
KCC=/opt/keycloak/conf/keycloak.conf
sed -i "s|^hostname=.*|hostname=http://$IP:$KC_PORT|" "$KCC"
systemctl restart keycloak
wait_http http://127.0.0.1:9000/health/ready 180 || die "Keycloak not ready"
KCADM=/opt/keycloak/bin/kcadm.sh
export JAVA_HOME=/opt/java21
CFG=$(mktemp)
$KCADM config credentials --config "$CFG" --server http://127.0.0.1:8180 --realm master --user "$KEYCLOAK_ADMIN_USER" --password "$KEYCLOAK_ADMIN_PASSWORD" >/dev/null
$KCADM update realms/"${KEYCLOAK_REALM:-ngo}" --config "$CFG" -s sslRequired=NONE \
  -s 'smtpServer.host=127.0.0.1' -s 'smtpServer.port=1025' -s 'smtpServer.from=no-reply@yogdaan.co.in' \
  -s 'smtpServer.fromDisplayName=Yogdaan (test)' -s 'smtpServer.auth=false' -s 'smtpServer.starttls=false' -s 'smtpServer.ssl=false'
rm -f "$CFG"
ok "realm ${KEYCLOAK_REALM:-ngo}: Require SSL = none (TEST MODE), e-mail via Mailpit"

log "restart the services with the new settings (discovery first, gateway last)"
for entry in "${SERVICES[@]}"; do
  read -r svc port _ <<<"$entry"
  systemctl restart "yogdaan@$svc"
  wait_http "http://127.0.0.1:$port/actuator/health" 240 || die "$svc did not restart (journalctl -u yogdaan@$svc)"
done
ok "all services restarted"

log "checks"
check() { if eval "$2" >/dev/null 2>&1; then ok "$1"; else warn "$1: FAILED"; fi; }
check "API on :80 (unauthenticated /api/v1/me -> 401)" "[ \"\$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1/api/v1/me)\" = 401 ]"
check "sign-in discovery says issuer http://$IP:$KC_PORT" "curl -fsS http://127.0.0.1:$KC_PORT/realms/${KEYCLOAK_REALM:-ngo}/.well-known/openid-configuration | jq -e --arg i \"$KEYCLOAK_ISSUERS\" '.issuer == \$i'"
check "internal paths blocked on :80" "[ \"\$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1/actuator/health)\" = 404 ]"
check "Keycloak admin blocked on :$KC_PORT" "[ \"\$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:$KC_PORT/admin/)\" = 404 ]"
check "mailbox asks for a password" "[ \"\$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:$MAIL_UI_PORT/)\" = 401 ]"
echo "MAILBOX: http://$IP:$MAIL_UI_PORT  user: test  password: $(cat "$PWF")"
