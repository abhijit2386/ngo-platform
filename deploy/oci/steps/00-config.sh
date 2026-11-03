#!/usr/bin/env bash
# Settings: installs files/yogdaan.env.prod (copied from the laptop) as /etc/yogdaan/yogdaan.env. Values already on the
# server are kept when the new file leaves them empty (so generated passwords never change), and empty secrets are
# generated once.
source "$(dirname "$0")/../lib.sh"
NEW="$KIT_DIR/files/yogdaan.env.prod"
[ -f "$NEW" ] || die "files/yogdaan.env.prod missing in the kit (create config/yogdaan.env.prod on the laptop)"
id "$APP_USER" >/dev/null 2>&1 || useradd --system --home-dir "$APP_DIR" --shell /sbin/nologin "$APP_USER"
install -d -m 0750 -o root -g "$APP_USER" /etc/yogdaan
install -d -m 0700 -o root -g root "$SECRETS_DIR"

tmp=$(mktemp)
tr -d '\r' < "$NEW" > "$tmp"
if [ -f "$ENV_FILE" ]; then
  while IFS= read -r line; do
    case "$line" in ''|\#*) continue ;; esac
    key="${line%%=*}"; val="${line#*=}"
    old=$(grep -m1 "^${key}=" "$ENV_FILE" | cut -d= -f2- || true)
    if [ -z "$val" ] && [ -n "$old" ]; then sed -i "s|^${key}=.*|${key}=${old//|/\\|}|" "$tmp"; fi
  done < "$tmp"
fi
bash -n "$tmp" || die "settings file has a syntax error (quote values with spaces or brackets: KEY=\"a b\")"
install -m 0640 -o root -g "$APP_USER" "$tmp" "$ENV_FILE"
rm -f "$tmp"

for key in KEYCLOAK_ADMIN_PASSWORD KEYCLOAK_ADMIN_CLIENT_SECRET KEYCLOAK_DB_PASSWORD DB_PASSWORD INTERNAL_TOKEN \
           RECEIPT_LINK_SECRET DOCUMENT_LINK_SECRET OTP_SECRET PAYMENTS_MOCK_SECRET BILLING_WEBHOOK_SECRET; do
  if [ -z "$(grep -m1 "^${key}=" "$ENV_FILE" | cut -d= -f2- || true)" ]; then set_env "$key" "$(gen_secret)"; ok "generated $key"; fi
done

load_env
require_env DOMAIN API_HOST AUTH_HOST
# Staged start (owner, 3 Nov 2026): e-mail, bucket and certificate e-mail may come later.
if [ -z "${S3_ENDPOINT:-}" ] || [ -z "${S3_ACCESS_KEY:-}" ] || [ -z "${S3_SECRET_KEY:-}" ]; then
  set_env STORAGE_TYPE local
  set_env STORAGE_ROOT /var/lib/yogdaan/files
  warn "no bucket keys yet: files are kept on this server (/var/lib/yogdaan/files) until S3_* are set"
fi
[ -n "${MAIL_HOST:-}" ] || warn "no SMTP yet: e-mails (sign-up confirmation, receipts) are not sent until MAIL_* are set"
[ -n "${ACME_EMAIL:-}" ] || warn "ACME_EMAIL empty: needed for the caddy step (HTTPS)"
[ "${PAYMENTS_MOCK_ENABLED:-false}" = false ] || warn "PAYMENTS_MOCK_ENABLED=true: test payments create real-looking donations"
[ "${BILLING_MOCK_ENABLED:-false}" = false ] || warn "BILLING_MOCK_ENABLED=true"
ok "settings in $ENV_FILE (readable by root and the $APP_USER service user only)"
