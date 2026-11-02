# Starts the container-free development stack: PostgreSQL, Keycloak, Mailpit, Eureka and the selected services.
#   -Services identity-service,ngo-registry-service,...  (default: the core set below)
param([string[]]$Services = @('identity-service', 'ngo-registry-service', 'subscription-service', 'notification-service',
  'campaign-project-service', 'donation-payment-service', 'receipt-service', 'document-service', 'api-gateway'),
  # Extra copies of receipt-service (ports 8097, 8107, ...), all registered in Eureka: the gateway spreads requests over
  # them and their workers share the receipt queue (SKIP LOCKED). Used by the load test.
  [int]$ReceiptCopies = 1,
  # Compiler mode for receipt-service only (crash investigation 2026-11-02): c1 = -XX:TieredStopAtLevel=1 (like every
  # other service), full = normal tiered JIT (C1 + C2), int = interpreter only (-Xint, slow; proves whether the JIT is involved).
  [ValidateSet('c1', 'full', 'int')][string]$ReceiptJit = 'c1',
  # Logs every JIT compilation of receipt-service to its log (the last lines before a crash show what was compiling).
  [switch]$ReceiptDiag)
$ErrorActionPreference = 'Continue'
$Repo = 'D:\ngo-platform'; $Tools = 'D:\tools'; $KcVer = '26.7.2'
$jdk = (Get-ChildItem $Tools -Directory -Filter 'jdk-21*' | Select-Object -First 1).FullName
$env:JAVA_HOME = $jdk
$log = "$Repo\logs\start-local.log"
Set-Content "$Repo\logs\start-local.status" 'RUNNING'
Set-Content $log "[$(Get-Date -Format s)] start-local"
# Master configuration (config\yogdaan.env, never committed; template: config\yogdaan.env.example). Only lines with a
# value are applied, so empty lines keep each service's development default. Values are not logged.
$cfg = "$Repo\config\yogdaan.env"
if (Test-Path $cfg) {
  $n = 0
  foreach ($line in Get-Content $cfg) {
    if ($line -match '^\s*([A-Z][A-Z0-9_]*)\s*=\s*(.*?)\s*$' -and $Matches[2] -ne '') {
      Set-Item -Path "env:$($Matches[1])" -Value $Matches[2]; $n++
    }
  }
  Add-Content $log "master configuration: $n settings from config\yogdaan.env"
}
function Log($s) { "$s" | Tee-Object -FilePath $log -Append | Write-Host }
function Port-Open($p) { try { $c = New-Object Net.Sockets.TcpClient; $c.Connect('127.0.0.1', $p); $c.Close(); $true } catch { $false } }

$ports = [ordered]@{ 'discovery-server' = 8761; 'identity-service' = 8082; 'ngo-registry-service' = 8083;
  'subscription-service' = 8084; 'donor-service' = 8085; 'donation-payment-service' = 8086; 'receipt-service' = 8087;
  'campaign-project-service' = 8088; 'ngo-operations-service' = 8089; 'helpdesk-service' = 8090;
  'notification-service' = 8091; 'reporting-service' = 8092; 'document-service' = 8093; 'api-gateway' = 8080 }

# 1. PostgreSQL
if (-not (Port-Open 5432)) {
  Log 'starting PostgreSQL'
  $pg = Start-Process -FilePath "$Tools\pgsql\bin\pg_ctl.exe" -ArgumentList '-D', "`"$Repo\.local\pgdata`"", '-l', "`"$Repo\logs\postgres.log`"", '-w', 'start' `
          -RedirectStandardOutput "$Repo\logs\pg_ctl-$(Get-Date -Format yyyyMMddHHmmss).out" -RedirectStandardError "$Repo\logs\pg_ctl-$(Get-Date -Format yyyyMMddHHmmss).err" -WindowStyle Hidden -PassThru
  $pg.WaitForExit(); Log "pg_ctl start exit=$($pg.ExitCode)"
}
# 2. Keycloak (dev mode, embedded H2 store, realm imported from data\import)
if (-not (Port-Open 8180)) {
  Log 'starting Keycloak'
  $env:KC_BOOTSTRAP_ADMIN_USERNAME = 'admin'; $env:KC_BOOTSTRAP_ADMIN_PASSWORD = 'dev-only-admin'
  $env:JAVA_OPTS_KC_HEAP = '-Xms64m -Xmx384m'
  Start-Process -FilePath "$Tools\keycloak-$KcVer\bin\kc.bat" -ArgumentList 'start-dev', '--http-port=8180', '--import-realm' `
    -RedirectStandardOutput "$Repo\logs\keycloak.log" -RedirectStandardError "$Repo\logs\keycloak.err.log" -WindowStyle Hidden
}
# 3. Mailpit (SMTP 1025, web UI http://localhost:8025)
if (-not (Port-Open 8025)) {
  Log 'starting Mailpit'
  Start-Process -FilePath "$Tools\mailpit\mailpit.exe" -ArgumentList '--listen', '127.0.0.1:8025', '--smtp', '127.0.0.1:1025' `
    -RedirectStandardOutput "$Repo\logs\mailpit.log" -RedirectStandardError "$Repo\logs\mailpit.err.log" -WindowStyle Hidden
}

# 4. Spring Boot: discovery first, then services, gateway last.
$order = @('discovery-server') + ($Services | Where-Object { $_ -ne 'api-gateway' -and $_ -ne 'discovery-server' })
for ($k = 2; $k -le $ReceiptCopies; $k++) { $ports["receipt-service-$k"] = 8087 + 10 * ($k - 1); $order += "receipt-service-$k" }
if ($Services -contains 'api-gateway') { $order += 'api-gateway' }
$missing = $order | Where-Object { -not (Test-Path "$Repo\services\$($_ -replace '^receipt-service-\d+$', 'receipt-service')\target\app.jar") }
if ($missing) { Log "missing jars: $($missing -join ', ') - run build-backend.cmd first"; Set-Content "$Repo\logs\start-local.status" 'NO_JARS'; exit 1 }

$env:STORAGE_ROOT = "$Repo\.local\files"
$env:LOG_FORMAT = 'ecs'
# Development only: staff may credit test donations to live campaigns until online payments exist.
$env:CAMPAIGN_TEST_DONATIONS = 'true'
$pidFile = "$Repo\logs\backend.pids"
foreach ($svc in $order) {
  if (Port-Open $ports[$svc]) { Log "already running $svc"; continue }
  $out = "$Repo\logs\svc-$svc.log"
  $copy = $svc -like 'receipt-service-*'
  $port = $ports[$svc]
  if ($copy) { $env:PORT = "$port"; $svc = 'receipt-service' } else { Remove-Item env:PORT -ErrorAction SilentlyContinue }
  # receipt-service renders PDFs (Java2D text shaping, image encoding run in native code): a 512k stack let it crash
  # with an access violation and no report (2026-11-01), so it gets the JDK default 1 MB and a little more heap.
  $stack = if ($svc -eq 'receipt-service') { '-Xss1m' } else { '-Xss512k' }
  $heap = if ($svc -eq 'receipt-service') { '-Xmx320m' } else { '-Xmx256m' }
  $jit = if ($svc -ne 'receipt-service' -or $ReceiptJit -eq 'c1') { '-XX:TieredStopAtLevel=1' } elseif ($ReceiptJit -eq 'int') { '-Xint' } else { '-XX:+TieredCompilation' }
  $diag = if ($svc -eq 'receipt-service' -and $ReceiptDiag) { '-XX:+PrintCompilation' } else { '-Dngo.diag=off' }
  $jargs = @('-Xms48m', $heap, '-XX:MaxMetaspaceSize=160m', '-XX:+UseSerialGC', $jit, $diag,
            $stack, "-XX:ErrorFile=$Repo\logs\hs_err_$svc`_%p.log", '-Dfile.encoding=UTF-8', '-jar', "$Repo\services\$svc\target\app.jar")
  $p = Start-Process -FilePath "$jdk\bin\java.exe" -ArgumentList $jargs -WorkingDirectory "$Repo\services\$svc" `
        -RedirectStandardOutput $out -RedirectStandardError "$out.err" -WindowStyle Hidden -PassThru
  if ($copy) { Remove-Item env:PORT -ErrorAction SilentlyContinue }
  Add-Content $pidFile "$($p.Id) $svc"
  Log "started $svc pid=$($p.Id) port=$port $(if ($svc -eq 'receipt-service') { "jit=$ReceiptJit" })"
  if ($svc -eq 'discovery-server') {
    for ($i = 0; $i -lt 60 -and -not (Port-Open 8761); $i++) { Start-Sleep 2 }
  }
}

Log '=== waiting for health'
$deadline = (Get-Date).AddMinutes(12)
$pending = [System.Collections.ArrayList]@($order)
while ($pending.Count -gt 0 -and (Get-Date) -lt $deadline) {
  Start-Sleep -Seconds 5
  foreach ($svc in @($pending)) {
    try {
      $r = Invoke-RestMethod -Uri "http://localhost:$($ports[$svc])/actuator/health" -TimeoutSec 3
      if ($r.status -eq 'UP') { Log "UP   $svc :$($ports[$svc])"; $pending.Remove($svc) }
    } catch { }
  }
}
foreach ($svc in $pending) { Log "DOWN $svc (see logs\svc-$svc.log)" }
$kc = try { (Invoke-WebRequest -UseBasicParsing 'http://localhost:8180/realms/ngo/.well-known/openid-configuration' -TimeoutSec 30).StatusCode } catch { 'DOWN' }
Log "keycloak realm ngo: $kc"
Set-Content "$Repo\logs\start-local.status" ($(if ($pending.Count -eq 0 -and $kc -eq 200) { 'OK' } else { 'PARTIAL' }))
