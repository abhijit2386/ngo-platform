# One-time setup of the container-free local stack (all open source):
#   PostgreSQL 17 (portable binaries), Keycloak 26 (runs on our JDK 21), Mailpit (test mailbox).
# Idempotent: re-running skips what is already in place. Log: logs\setup-local.log
$ErrorActionPreference = 'Continue'
$Repo = 'D:\ngo-platform'; $Tools = 'D:\tools'; $Dl = "$Tools\_dl"; $Local = "$Repo\.local"
$log = "$Repo\logs\setup-local.log"
New-Item -ItemType Directory -Force -Path $Dl, $Local, "$Repo\logs" | Out-Null
Set-Content $log "[$(Get-Date -Format s)] setup-local"
function Log($s) { "$s" | Tee-Object -FilePath $log -Append | Write-Host }
function Fetch($url, $file) {
  if (Test-Path "$Dl\$file") { Log "have $file"; return $true }
  Log "downloading $file"
  & curl.exe -L --fail --retry 3 -sS -o "$Dl\$file.part" $url
  if ($LASTEXITCODE -ne 0) { Log "DOWNLOAD FAILED: $url"; return $false }
  Move-Item "$Dl\$file.part" "$Dl\$file" -Force; return $true
}

$PgVer = '17.11'; $KcVer = '26.7.2'
$ok = $true
$ok = (Fetch 'https://sbp.enterprisedb.com/getfile.jsp?fileid=1260616' "postgresql-$PgVer-windows-x64-binaries.zip") -and $ok
$ok = (Fetch "https://github.com/keycloak/keycloak/releases/download/$KcVer/keycloak-$KcVer.zip" "keycloak-$KcVer.zip") -and $ok
$ok = (Fetch 'https://github.com/axllent/mailpit/releases/latest/download/mailpit-windows-amd64.zip' 'mailpit-windows-amd64.zip') -and $ok
if (-not $ok) { Set-Content "$Repo\logs\setup-local.status" 'DOWNLOAD_FAIL'; exit 1 }

if (-not (Test-Path "$Tools\pgsql\bin\pg_ctl.exe")) { Log 'extracting PostgreSQL'; & tar -xf "$Dl\postgresql-$PgVer-windows-x64-binaries.zip" -C $Tools }
if (-not (Test-Path "$Tools\keycloak-$KcVer\bin\kc.bat")) { Log 'extracting Keycloak'; & tar -xf "$Dl\keycloak-$KcVer.zip" -C $Tools }
if (-not (Test-Path "$Tools\mailpit\mailpit.exe")) { Log 'extracting Mailpit'; New-Item -ItemType Directory -Force "$Tools\mailpit" | Out-Null; & tar -xf "$Dl\mailpit-windows-amd64.zip" -C "$Tools\mailpit" }

# PostgreSQL cluster (data under the repo's .local folder, git-ignored). Superuser password is dev-only.
$pgBin = "$Tools\pgsql\bin"; $pgData = "$Local\pgdata"
if (-not (Test-Path "$pgData\PG_VERSION")) {
  Log 'initdb'
  Set-Content "$Local\pwfile" 'dev-only-password'
  & "$pgBin\initdb.exe" -D $pgData -U postgres --pwfile="$Local\pwfile" -A scram-sha-256 -E UTF8 --locale=C *> "$Repo\logs\initdb.log"
  Remove-Item "$Local\pwfile"
  Add-Content "$pgData\postgresql.conf" "`nlisten_addresses = 'localhost'`nport = 5432`nshared_buffers = 64MB`nmax_connections = 150`n"
}
$env:PGPASSWORD = 'dev-only-password'
# pg_ctl leaves postgres running: start it detached with file output so no pipe is held open.
$pg = Start-Process -FilePath "$pgBin\pg_ctl.exe" -ArgumentList '-D', "`"$pgData`"", '-l', "`"$Repo\logs\postgres.log`"", '-w', 'start' `
        -RedirectStandardOutput "$Repo\logs\pg_ctl-$(Get-Date -Format yyyyMMddHHmmss).out" -RedirectStandardError "$Repo\logs\pg_ctl-$(Get-Date -Format yyyyMMddHHmmss).err" -WindowStyle Hidden -PassThru
$pg.WaitForExit(); Log "pg_ctl start exit=$($pg.ExitCode)"
$exists = & "$pgBin\psql.exe" -h localhost -U postgres -tAc "SELECT 1 FROM pg_roles WHERE rolname='identity'"
if ($exists -ne '1') {
  Log 'creating service databases'
  & "$pgBin\psql.exe" -h localhost -U postgres -v ON_ERROR_STOP=1 -f "$Repo\infrastructure\database\init-databases.sql" *> "$Repo\logs\init-databases.log"
  Log "init-databases exit=$LASTEXITCODE"
}

# Keycloak realm import (start-dev imports data\import\*.json on start).
New-Item -ItemType Directory -Force "$Tools\keycloak-$KcVer\data\import" | Out-Null
Copy-Item "$Repo\infrastructure\keycloak\ngo-realm.json" "$Tools\keycloak-$KcVer\data\import\ngo-realm.json" -Force
Log 'setup-local done'
Set-Content "$Repo\logs\setup-local.status" 'OK'
