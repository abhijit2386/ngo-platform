#!/usr/bin/env bash
# Base: OS check, OpenJDK 21, fonts for PDFs (rupee sign, Devanagari), firewall (22/80/443 only), folders.
source "$(dirname "$0")/../lib.sh"
check_os
log "packages"
dnf_install java-21-openjdk-headless curl tar gzip unzip openssl jq policycoreutils-python-utils firewalld fontconfig
# font package names differ between Oracle Linux 8 and 9: try each, warn instead of failing
for f in dejavu-sans-fonts google-noto-sans-devanagari-fonts google-noto-sans-devanagari-vf-fonts; do
  dnf -y -q install "$f" >/dev/null 2>&1 && ok "font package $f" || true
done
JAVA_BIN=$(ls -d /usr/lib/jvm/java-21-openjdk*/bin/java 2>/dev/null | head -1)
[ -x "$JAVA_BIN" ] || die "OpenJDK 21 not found after install"
ln -sfn "$(dirname "$(dirname "$JAVA_BIN")")" /opt/java21
ok "$(/opt/java21/bin/java -version 2>&1 | head -1)"

log "fonts at the paths the PDF code looks for"
link_font() { # file-name target
  local src; src=$(find /usr/share/fonts -name "$1" 2>/dev/null | head -1)
  [ -n "$src" ] || { warn "font $1 not found"; return 0; }
  install -d "$(dirname "$2")"; [ "$src" = "$2" ] || ln -sfn "$src" "$2"; ok "$2"
}
link_font DejaVuSans.ttf /usr/share/fonts/truetype/dejavu/DejaVuSans.ttf
link_font DejaVuSans-Bold.ttf /usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf
link_font DejaVuSans.ttf /usr/share/fonts/dejavu/DejaVuSans.ttf
link_font DejaVuSans-Bold.ttf /usr/share/fonts/dejavu/DejaVuSans-Bold.ttf
link_font NotoSansDevanagari-Regular.ttf /usr/share/fonts/truetype/noto/NotoSansDevanagari-Regular.ttf
fc-cache -f >/dev/null 2>&1 || true

log "firewall: only ssh, http, https answer from outside (also when every OCI port is open)"
systemctl enable --now firewalld >/dev/null
for s in ssh http https; do firewall-cmd -q --permanent --add-service="$s"; done
firewall-cmd -q --reload
ok "open: $(firewall-cmd --list-services)"

log "user and folders"
id "$APP_USER" >/dev/null 2>&1 || useradd --system --home-dir "$APP_DIR" --shell /sbin/nologin "$APP_USER"
install -d -m 0755 -o "$APP_USER" -g "$APP_USER" "$APP_DIR" "$APP_DIR/services"
install -d -m 0750 -o "$APP_USER" -g "$APP_USER" "$LOG_DIR"
install -d -m 0750 -o "$APP_USER" -g "$APP_USER" /var/lib/yogdaan
timedatectl set-timezone Asia/Kolkata || true
ok "timezone $(timedatectl show -p Timezone --value 2>/dev/null || echo '?')"
