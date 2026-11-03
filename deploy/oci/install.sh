#!/usr/bin/env bash
# Yogdaan server install, one component at a time (Oracle Linux 8/9, x86_64). Every step can be re-run.
#   sudo ./install.sh config      settings file /etc/yogdaan/yogdaan.env (+ generated secrets)
#   sudo ./install.sh base        Java 21, fonts, firewall, user, folders
#   sudo ./install.sh postgres    PostgreSQL + the service databases
#   sudo ./install.sh keycloak    Keycloak (production realm, no test users)
#   sudo ./install.sh caddy       HTTPS for yogdaan.co.in, api., auth. (needs DNS pointing here)
#   sudo ./install.sh services    the Yogdaan services (systemd, Eureka discovery) - also used for updates
#   sudo ./install.sh elk         Elasticsearch + Kibana + Filebeat (logs of all services)
#   sudo ./install.sh backup      nightly database backup to the OCI bucket
#   sudo ./install.sh smoke       checks everything answers
#   sudo ./install.sh all         all of the above in order
source "$(dirname "$0")/lib.sh"
need_root
STEPS=(config base postgres keycloak caddy services elk backup smoke)
run_step() {
  local f
  f=$(ls "$KIT_DIR"/steps/[0-9][0-9]-"$1".sh 2>/dev/null | head -1)
  [ -n "$f" ] || die "unknown step '$1' (steps: ${STEPS[*]} all)"
  log "step: $1"
  bash "$f"
  ok "step $1 done"
}
[ $# -ge 1 ] || { sed -n '2,13p' "$0"; exit 1; }
# keep only the two newest deploy kits (each holds all jars, about 1 GB)
if [ -d /opt/yogdaan-kit ]; then
  ls -1dt /opt/yogdaan-kit/2*/ 2>/dev/null | tail -n +3 | while read -r old; do [ "${old%/}" != "$KIT_DIR" ] && rm -rf "$old"; done
fi
rm -f /tmp/yogdaan-kit-*.tgz
for s in "$@"; do
  if [ "$s" = all ]; then for x in "${STEPS[@]}"; do run_step "$x"; done; else run_step "$s"; fi
done
