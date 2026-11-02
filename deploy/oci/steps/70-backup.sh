#!/usr/bin/env bash
# Nightly backup (02:30 India time): all databases incl. Keycloak (pg_dumpall, gzip) to S3_BACKUP_BUCKET in OCI Object
# Storage through rclone (open source); 7 days kept on the server, 30 days in the bucket. Uploaded files (receipts,
# bills, vault) already live in the OCI bucket. Restore: see deploy/oci/README.md.
source "$(dirname "$0")/../lib.sh"
load_env
require_env S3_ENDPOINT S3_ACCESS_KEY S3_SECRET_KEY S3_BACKUP_BUCKET
if ! command -v rclone >/dev/null 2>&1; then
  log "install rclone"
  dnf -y -q install https://downloads.rclone.org/rclone-current-linux-amd64.rpm >/dev/null
fi

cat > /usr/local/bin/yogdaan-backup <<'SCRIPT'
#!/usr/bin/env bash
set -Eeuo pipefail
set -a; . /etc/yogdaan/yogdaan.env; set +a
export RCLONE_CONFIG_OCI_TYPE=s3 RCLONE_CONFIG_OCI_PROVIDER=Other RCLONE_CONFIG_OCI_ENDPOINT="$S3_ENDPOINT" \
       RCLONE_CONFIG_OCI_REGION="${S3_REGION:-ap-mumbai-1}" RCLONE_CONFIG_OCI_ACCESS_KEY_ID="$S3_ACCESS_KEY" \
       RCLONE_CONFIG_OCI_SECRET_ACCESS_KEY="$S3_SECRET_KEY" RCLONE_CONFIG_OCI_FORCE_PATH_STYLE=true
DIR=/var/backups/yogdaan; install -d -m 0700 "$DIR"
FILE="$DIR/yogdaan-db-$(date +%Y%m%d-%H%M).sql.gz"
sudo -u postgres /usr/pgsql-*/bin/pg_dumpall --clean --if-exists | gzip -9 > "$FILE"
[ "$(stat -c %s "$FILE")" -gt 1000 ] || { echo "backup too small: $FILE"; exit 1; }
rclone mkdir "oci:$S3_BACKUP_BUCKET" >/dev/null 2>&1 || true
rclone copy "$FILE" "oci:$S3_BACKUP_BUCKET/db/" --s3-no-check-bucket
find "$DIR" -name 'yogdaan-db-*.sql.gz' -mtime +7 -delete
rclone delete "oci:$S3_BACKUP_BUCKET/db/" --min-age 30d --s3-no-check-bucket || true
echo "backup ok: $FILE -> $S3_BACKUP_BUCKET/db/"
SCRIPT
chmod 0700 /usr/local/bin/yogdaan-backup

cat > /etc/systemd/system/yogdaan-backup.service <<'UNIT'
[Unit]
Description=Yogdaan database backup to OCI Object Storage
After=postgresql-17.service
[Service]
Type=oneshot
ExecStart=/usr/local/bin/yogdaan-backup
UNIT
sed -i "s/postgresql-17/postgresql-${PG_MAJOR}/" /etc/systemd/system/yogdaan-backup.service
cat > /etc/systemd/system/yogdaan-backup.timer <<'UNIT'
[Unit]
Description=Nightly Yogdaan database backup
[Timer]
OnCalendar=*-*-* 02:30:00
Persistent=true
[Install]
WantedBy=timers.target
UNIT
systemctl daemon-reload
systemctl enable --now yogdaan-backup.timer >/dev/null
log "first backup now"
/usr/local/bin/yogdaan-backup || die "backup failed: check S3_* settings and that the bucket '$S3_BACKUP_BUCKET' is in the S3 compatibility compartment"
ok "nightly at 02:30 ($(systemctl list-timers yogdaan-backup.timer --no-legend | awk '{print $1, $2}'))"
