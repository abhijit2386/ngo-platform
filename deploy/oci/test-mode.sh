#!/usr/bin/env bash
# TEST MODE (owner, 3 Nov 2026): app testing before the real domain / real e-mail exist. Names under sslip.io point
# to the server's IP by themselves (no DNS work), and Caddy gets free Let's Encrypt certificates for them, so sign-in
# runs over HTTPS (Keycloak 26 refuses plain-HTTP sign-in: cookie_not_found, no password field on register):
#   https://api.<ip-with-dashes>.sslip.io   -> gateway (API)       /internal, /actuator blocked
#   https://auth.<ip-with-dashes>.sslip.io  -> Keycloak (sign-in)  /admin blocked
#   https://mail.<ip-with-dashes>.sslip.io  -> Mailpit test mailbox (sign-up mails, phone OTPs), password protected
# Every e-mail goes to Mailpit, none reaches a real person. Go-live: real DOMAIN/API_HOST/AUTH_HOST and MAIL_* in the
# settings, then './install.sh config caddy' and restart the services.
# Usage: sudo PUBLIC_IP=130.210.11.54 ./test-mode.sh
source "$(dirname "$0")/lib.sh"
need_root
load_env
IP="${PUBLIC_IP:?set PUBLIC_IP}"
BASE="${IP//./-}.sslip.io"
API="api.$BASE"; AUTH="auth.$BASE"; MAILUI="mail.$BASE"

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

log "settings: $API / $AUTH, e-mail to Mailpit"
set_env PUBLIC_API_BASE "https://$API"; set_env PUBLIC_API_URL "https://$API"; set_env PUBLIC_WEB_URL "https://$API"
set_env KEYCLOAK_ISSUERS "https://$AUTH/realms/${KEYCLOAK_REALM:-ngo}"
set_env MAIL_HOST 127.0.0.1; set_env MAIL_PORT 1025; set_env MAIL_AUTH false; set_env MAIL_STARTTLS false
set_env MAIL_USER ""; set_env MAIL_PASSWORD ""
load_env

log "Caddy: HTTPS for $API, $AUTH, $MAILUI (Let's Encrypt; needs ports 80 and 443 open)"
if ! rpm -q caddy >/dev/null 2>&1; then
  dnf_install 'dnf-command(copr)'; dnf -y -q copr enable @caddy/caddy >/dev/null; dnf_install caddy
fi
setsebool -P httpd_can_network_connect 1 2>/dev/null || true
PWF="$SECRETS_DIR/mailpit-ui-password"
[ -s "$PWF" ] || { gen_secret | cut -c1-16 > "$PWF"; chmod 0600 "$PWF"; }
HASH=$(caddy hash-password --plaintext "$(cat "$PWF")")
cat > /etc/caddy/Caddyfile <<CADDY
{
	${ACME_EMAIL:+email $ACME_EMAIL}
}

(common) {
	encode gzip
	header -Server
	log {
		output stderr
	}
}

$API {
	import common
	@internal path /internal/* /actuator /actuator/*
	respond @internal 404
	request_body {
		max_size 25MB
	}
	reverse_proxy 127.0.0.1:8080
}

$AUTH {
	import common
	@admin path /admin /admin/*
	respond @admin 404
	reverse_proxy 127.0.0.1:8180
}

$MAILUI {
	import common
	basic_auth {
		test $HASH
	}
	reverse_proxy 127.0.0.1:8026
}
CADDY
caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile >/dev/null 2>&1 || die "Caddyfile invalid"
systemctl enable caddy >/dev/null; systemctl restart caddy
firewall-cmd -q --permanent --remove-port=8081/tcp 2>/dev/null || true; firewall-cmd -q --permanent --remove-port=8025/tcp 2>/dev/null || true
firewall-cmd -q --permanent --add-service=http; firewall-cmd -q --permanent --add-service=https; firewall-cmd -q --reload
ok "firewall: $(firewall-cmd --list-services) ports: $(firewall-cmd --list-ports)"

log "Keycloak answers as https://$AUTH; realm e-mail -> Mailpit"
KCC=/opt/keycloak/conf/keycloak.conf
sed -i "s|^hostname=.*|hostname=https://$AUTH|" "$KCC"
systemctl restart keycloak
wait_http http://127.0.0.1:9000/health/ready 180 || die "Keycloak not ready"
KCADM=/opt/keycloak/bin/kcadm.sh
export JAVA_HOME=/opt/java21
CFG=$(mktemp)
$KCADM config credentials --config "$CFG" --server http://127.0.0.1:8180 --realm master --user "$KEYCLOAK_ADMIN_USER" --password "$KEYCLOAK_ADMIN_PASSWORD" >/dev/null
$KCADM update realms/"${KEYCLOAK_REALM:-ngo}" --config "$CFG" -s sslRequired=external -s loginTheme=yogdaan \
  -s 'smtpServer.host=127.0.0.1' -s 'smtpServer.port=1025' -s 'smtpServer.from=no-reply@yogdaan.co.in' \
  -s 'smtpServer.fromDisplayName=Yogdaan (test)' -s 'smtpServer.auth=false' -s 'smtpServer.starttls=false' -s 'smtpServer.ssl=false'
rm -f "$CFG"
ok "realm ${KEYCLOAK_REALM:-ngo}: HTTPS required (external), e-mail via Mailpit"

log "restart the services with the new settings (discovery first, gateway last)"
for entry in "${SERVICES[@]}"; do
  read -r svc port _ <<<"$entry"
  systemctl restart "yogdaan@$svc"
  wait_http "http://127.0.0.1:$port/actuator/health" 240 || die "$svc did not restart (journalctl -u yogdaan@$svc)"
done
ok "all services restarted"

log "checks (HTTPS through Caddy; the first request per name fetches its certificate)"
check() { if eval "$2" >/dev/null 2>&1; then ok "$1"; else warn "$1: FAILED"; fi; }
sleep 5
check "https://$API answers (unauthenticated /api/v1/me -> 401)" "[ \"\$(curl -s -o /dev/null -w '%{http_code}' --retry 6 --retry-delay 5 --retry-all-errors https://$API/api/v1/me)\" = 401 ]"
check "https://$AUTH issuer" "curl -fsS --retry 6 --retry-delay 5 --retry-all-errors https://$AUTH/realms/${KEYCLOAK_REALM:-ngo}/.well-known/openid-configuration | jq -e --arg i \"$KEYCLOAK_ISSUERS\" '.issuer == \$i'"
check "internal paths blocked" "[ \"\$(curl -s -o /dev/null -w '%{http_code}' https://$API/actuator/health)\" = 404 ]"
check "Keycloak admin blocked" "[ \"\$(curl -s -o /dev/null -w '%{http_code}' https://$AUTH/admin/)\" = 404 ]"
check "mailbox asks for a password" "[ \"\$(curl -s -o /dev/null -w '%{http_code}' --retry 6 --retry-delay 5 --retry-all-errors https://$MAILUI/)\" = 401 ]"
echo "API: https://$API   SIGN-IN: https://$AUTH   MAILBOX: https://$MAILUI (user test, password $(cat "$PWF"))"
