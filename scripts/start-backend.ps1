# Builds (if needed) and starts all Spring Boot services natively on Windows against the Podman infrastructure.
param([switch]$Rebuild, [string[]]$Only)
$ErrorActionPreference = 'Continue'
$Repo = 'D:\ngo-platform'
$jdk = (Get-ChildItem D:\tools -Directory -Filter 'jdk-21*' | Select-Object -First 1).FullName
$env:JAVA_HOME = $jdk
$env:PATH = "$jdk\bin;D:\tools\apache-maven-3.9.9\bin;$env:PATH"
$log = "$Repo\logs\start-backend.log"
Set-Content "$Repo\logs\start-backend.status" 'RUNNING'
function Log($s) { $s | Tee-Object -FilePath $log -Append | Write-Host }
Set-Content $log "[$(Get-Date -Format s)] start-backend"

$services = [ordered]@{
  'identity-service' = 8082; 'ngo-registry-service' = 8083; 'subscription-service' = 8084; 'donor-service' = 8085;
  'donation-payment-service' = 8086; 'receipt-service' = 8087; 'campaign-project-service' = 8088;
  'ngo-operations-service' = 8089; 'helpdesk-service' = 8090; 'notification-service' = 8091;
  'reporting-service' = 8092; 'api-gateway' = 8080 }
if ($Only) { $all = $services; $services = [ordered]@{}; foreach ($o in $Only) { if ($all.Contains($o)) { $services[$o] = $all[$o] } } }

# Stop anything we started before.
& "$Repo\scripts\stop-backend.ps1" -Quiet

$needBuild = $Rebuild -or ($services.Keys | Where-Object { -not (Test-Path "$Repo\services\$_\target\app.jar") })
if ($needBuild) {
  Log '=== mvn package'
  Set-Location $Repo
  mvn -B -ntp -q package -DskipTests 2>&1 | ForEach-Object { Log $_ }
  if ($LASTEXITCODE -ne 0) { Set-Content "$Repo\logs\start-backend.status" 'BUILD_FAIL'; Log 'BUILD FAILED'; exit 1 }
}

$env:LOG_FORMAT = 'ecs'
$env:TRACING_SAMPLE = '1.0'
$pids = @()
foreach ($svc in $services.Keys) {
  $jar = "$Repo\services\$svc\target\app.jar"
  $out = "$Repo\logs\svc-$svc.log"
  $args = @('-Xms64m', '-Xmx256m', '-XX:+UseSerialGC', '-XX:TieredStopAtLevel=1', '-Dfile.encoding=UTF-8', '-jar', $jar)
  $p = Start-Process -FilePath "$jdk\bin\java.exe" -ArgumentList $args -WorkingDirectory "$Repo\services\$svc" `
        -RedirectStandardOutput $out -RedirectStandardError "$out.err" -WindowStyle Hidden -PassThru
  $pids += "$($p.Id) $svc"
  Log "started $svc pid=$($p.Id)"
}
Set-Content "$Repo\logs\backend.pids" $pids

Log '=== waiting for health'
$deadline = (Get-Date).AddMinutes(4)
$pending = [System.Collections.ArrayList]@($services.Keys)
while ($pending.Count -gt 0 -and (Get-Date) -lt $deadline) {
  Start-Sleep -Seconds 5
  foreach ($svc in @($pending)) {
    try {
      $r = Invoke-RestMethod -Uri "http://localhost:$($services[$svc])/actuator/health" -TimeoutSec 2
      if ($r.status -eq 'UP') { Log "UP   $svc :$($services[$svc])"; $pending.Remove($svc) }
    } catch { }
  }
}
foreach ($svc in $pending) { Log "DOWN $svc (see logs\svc-$svc.log)" }
Set-Content "$Repo\logs\start-backend.status" ($(if ($pending.Count -eq 0) { 'OK' } else { 'PARTIAL' }))
