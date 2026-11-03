#!/usr/bin/env bash
# Registers and enables Keycloak's standard required actions (Update Password, Verify Email, ...) in the realm.
# 'kc.sh import' of ngo-realm.json leaves them out, and without "Update Password" the register page has no password
# field (Password Validation only asks for one when that action exists); without "Verify Email" no mail is sent.
# Re-runnable. Used by steps/30-keycloak.sh; usage alone: sudo bash kc-required-actions.sh
source "$(dirname "$0")/lib.sh"
load_env
export JAVA_HOME=/opt/java21
K=/opt/keycloak/bin/kcadm.sh; C=$(mktemp); R="${KEYCLOAK_REALM:-ngo}"
wait_http http://127.0.0.1:9000/health/ready 180 || die "Keycloak not ready"
$K config credentials --config "$C" --server http://127.0.0.1:8180 --realm master --user "$KEYCLOAK_ADMIN_USER" --password "$KEYCLOAK_ADMIN_PASSWORD" >/dev/null
$K get authentication/unregistered-required-actions -r "$R" --config "$C" | jq -c '.[]' | while read -r a; do
  pid=$(jq -r .providerId <<<"$a"); name=$(jq -r .name <<<"$a")
  $K create authentication/register-required-action -r "$R" --config "$C" -s "providerId=$pid" -s "name=$name" >/dev/null && ok "registered $pid"
done
for pid in UPDATE_PASSWORD VERIFY_EMAIL UPDATE_PROFILE; do
  $K update "authentication/required-actions/$pid" -r "$R" --config "$C" -s enabled=true >/dev/null 2>&1 && ok "enabled $pid"
done
ok "required actions now: $($K get authentication/required-actions -r "$R" --config "$C" | jq -r '[.[] | select(.enabled) | .alias] | join(", ")')"
rm -f "$C"
