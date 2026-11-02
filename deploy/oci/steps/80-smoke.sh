#!/usr/bin/env bash
# Smoke test after an install or update. Read-only except one small test object written to and removed from the bucket.
source "$(dirname "$0")/../lib.sh"
load_env
pass=0; fail=0
check() { if eval "$2" >/dev/null 2>&1; then pass=$((pass+1)); echo "PASS  $1"; else fail=$((fail+1)); echo "FAIL  $1"; fi; }
code() { curl -s -o /dev/null -w '%{http_code}' --max-time 20 "$1"; }

log "server"
check "PostgreSQL running" "systemctl is-active postgresql-${PG_MAJOR}"
check "Keycloak running" "systemctl is-active keycloak"
check "Caddy running" "systemctl is-active caddy"
for entry in "${SERVICES[@]}"; do
  read -r svc port _ <<<"$entry"
  check "$svc healthy" "curl -fsS http://127.0.0.1:$port/actuator/health | grep -q UP"
done
check "all services registered in Eureka" "[ \$(curl -fsS -H 'Accept: application/json' http://127.0.0.1:8761/eureka/apps | jq '.applications.application | length') -ge $(( ${#SERVICES[@]} - 1 )) ]"
check "PDF fonts installed (rupee sign, Devanagari)" "[ -e /usr/share/fonts/truetype/dejavu/DejaVuSans.ttf ] && [ -e /usr/share/fonts/truetype/noto/NotoSansDevanagari-Regular.ttf ]"
check "test payments OFF" "[ \"\${PAYMENTS_MOCK_ENABLED:-false}\" = false ]"
check "only 22/80/443 on public addresses" "[ -z \"\$(ss -ltnH | awk '{print \$4}' | grep -vE '^(127\\.0\\.0\\.1|\\[::1\\]|\\[::ffff:127\\.0\\.0\\.1\\]):' | grep -vE ':(22|80|443)\$')\" ]"

log "from the internet (through Caddy, HTTPS)"
check "https://$AUTH_HOST sign-in discovery, issuer = KEYCLOAK_ISSUERS" "curl -fsS https://$AUTH_HOST/realms/${KEYCLOAK_REALM:-ngo}/.well-known/openid-configuration | jq -e --arg i \"$KEYCLOAK_ISSUERS\" '.issuer == \$i'"
check "https://$API_HOST answers (unauthenticated /api/v1/me -> 401)" "[ \"\$(code https://$API_HOST/api/v1/me)\" = 401 ]"
check "internal paths blocked from outside" "[ \"\$(code https://$API_HOST/actuator/health)\" = 404 ] && [ \"\$(code https://$API_HOST/internal/v1/ngos)\" = 404 ]"
check "Keycloak admin console blocked from outside" "[ \"\$(code https://$AUTH_HOST/admin/)\" = 404 ]"
check "https://$DOMAIN answers" "[ \"\$(code https://$DOMAIN/)\" != 000 ]"

log "storage and logs"
if command -v rclone >/dev/null 2>&1; then
  export RCLONE_CONFIG_OCI_TYPE=s3 RCLONE_CONFIG_OCI_PROVIDER=Other RCLONE_CONFIG_OCI_ENDPOINT="$S3_ENDPOINT" \
         RCLONE_CONFIG_OCI_REGION="${S3_REGION:-ap-mumbai-1}" RCLONE_CONFIG_OCI_ACCESS_KEY_ID="$S3_ACCESS_KEY" \
         RCLONE_CONFIG_OCI_SECRET_ACCESS_KEY="$S3_SECRET_KEY" RCLONE_CONFIG_OCI_FORCE_PATH_STYLE=true
  obj="smoke/$(date +%s).txt"
  check "bucket $S3_BUCKET: write, read back, delete" "echo yogdaan-smoke | rclone rcat oci:$S3_BUCKET/$obj --s3-no-check-bucket && [ \"\$(rclone cat oci:$S3_BUCKET/$obj)\" = yogdaan-smoke ] && rclone deletefile oci:$S3_BUCKET/$obj"
else
  echo "SKIP  bucket test (rclone comes with './install.sh backup')"
fi
if [ -s "$SECRETS_DIR/elastic-password" ]; then
  check "service logs arrive in Elasticsearch" "curl -fsS --cacert /etc/elasticsearch/certs/http_ca.crt -u elastic:\$(cat $SECRETS_DIR/elastic-password) 'https://127.0.0.1:9200/logs-yogdaan-default/_count' | jq -e '.count > 0'"
fi
echo
echo "smoke: $pass passed, $fail failed"
[ "$fail" -eq 0 ]
