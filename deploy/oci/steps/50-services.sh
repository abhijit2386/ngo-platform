#!/usr/bin/env bash
# Yogdaan services as systemd units (yogdaan@<service>), Eureka discovery first, gateway last; every service on
# 127.0.0.1 only. Jars come from files/jars/<service>.jar; the previous jar is kept as app.jar.prev (rollback:
# ROLLBACK=1 sudo ./install.sh services). Only some services: ONLY="receipt-service reporting-service" sudo ./install.sh services
source "$(dirname "$0")/../lib.sh"
load_env
require_env DB_PASSWORD INTERNAL_TOKEN KEYCLOAK_ISSUERS
ONLY="${ONLY:-}"; ROLLBACK="${ROLLBACK:-0}"
install -d -m 0750 -o root -g "$APP_USER" /etc/yogdaan/services

cat > /etc/systemd/system/yogdaan@.service <<UNIT
[Unit]
Description=Yogdaan %i
After=network-online.target postgresql-${PG_MAJOR}.service keycloak.service
Wants=network-online.target

[Service]
User=${APP_USER}
EnvironmentFile=${ENV_FILE}
EnvironmentFile=/etc/yogdaan/services/%i.env
WorkingDirectory=${APP_DIR}/services/%i
ExecStart=/opt/java21/bin/java \$JAVA_OPTS -jar ${APP_DIR}/services/%i/app.jar
Restart=on-failure
RestartSec=10
SuccessExitStatus=143
TimeoutStopSec=40
LimitNOFILE=65536

[Install]
WantedBy=multi-user.target
UNIT

selected() { [ -z "$ONLY" ] || [[ " $ONLY " == *" $1 "* ]]; }

for entry in "${SERVICES[@]}"; do
  read -r svc port heap <<<"$entry"
  selected "$svc" || continue
  dir="$APP_DIR/services/$svc"
  install -d -m 0755 -o "$APP_USER" -g "$APP_USER" "$dir"
  if [ "$ROLLBACK" = 1 ]; then
    [ -f "$dir/app.jar.prev" ] || die "$svc: no previous jar to roll back to"
    mv "$dir/app.jar.prev" "$dir/app.jar"
  else
    jar="$KIT_DIR/files/jars/$svc.jar"
    [ -f "$jar" ] || die "$svc: files/jars/$svc.jar missing in the kit"
    if [ -f "$dir/app.jar" ] && ! cmp -s "$jar" "$dir/app.jar"; then cp -p "$dir/app.jar" "$dir/app.jar.prev"; fi
    install -m 0644 -o "$APP_USER" -g "$APP_USER" "$jar" "$dir/app.jar"
  fi
  stack=512k; [ "$svc" = receipt-service ] && stack=1m   # PDF text shaping needs the larger stack (crash 2026-11-01)
  mem_mb=$(( ${heap%m} + 448 ))
  cat > "/etc/yogdaan/services/$svc.env" <<ENVF
PORT=$port
SERVER_ADDRESS=127.0.0.1
BIND_ADDRESS=127.0.0.1
LOGGING_FILE_NAME=$LOG_DIR/$svc.log
LOGGING_STRUCTURED_FORMAT_FILE=ecs
JAVA_OPTS=-Xms64m -Xmx$heap -XX:MaxMetaspaceSize=192m -XX:CompressedClassSpaceSize=64m -XX:ReservedCodeCacheSize=96m -XX:MaxDirectMemorySize=64m -XX:+UseSerialGC -XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=30 -Xss$stack -XX:+ExitOnOutOfMemoryError -XX:ErrorFile=$LOG_DIR/hs_err_${svc}_%p.log -Djava.awt.headless=true -Dfile.encoding=UTF-8
ENVF
  chmod 0640 "/etc/yogdaan/services/$svc.env"; chgrp "$APP_USER" "/etc/yogdaan/services/$svc.env"
  install -d "/etc/systemd/system/yogdaan@$svc.service.d"
  printf '[Service]\nMemoryMax=%sM\n' "$mem_mb" > "/etc/systemd/system/yogdaan@$svc.service.d/memory.conf"
done
systemctl daemon-reload

log "start (discovery first, gateway last); each must answer its health check"
for entry in "${SERVICES[@]}"; do
  read -r svc port heap <<<"$entry"
  selected "$svc" || continue
  systemctl enable "yogdaan@$svc" >/dev/null
  systemctl restart "yogdaan@$svc"
  if wait_http "http://127.0.0.1:$port/actuator/health" 240; then ok "$svc UP on 127.0.0.1:$port"
  else die "$svc did not start: journalctl -u yogdaan@$svc -n 80 ; tail $LOG_DIR/$svc.log"; fi
done

n=$(curl -fsS -H 'Accept: application/json' http://127.0.0.1:8761/eureka/apps | jq '.applications.application | length' 2>/dev/null || echo 0)
ok "registered in Eureka: $n applications (expected $(( ${#SERVICES[@]} - 1 )))"
outside=$(ss -ltnH | awk '{print $4}' | grep -vE '^(127\.0\.0\.1|\[::1\]|\[::ffff:127\.0\.0\.1\]):' | grep -vE ':(22|80|443)$' || true)
[ -z "$outside" ] && ok "nothing but 22/80/443 listens on public addresses" || warn "listening on public addresses: $outside"
