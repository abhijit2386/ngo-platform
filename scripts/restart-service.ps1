# Stops one locally running service (found by its listening port), optionally rebuilds it (-Build; the jar is
# locked while it runs on Windows), and starts it again via start-local.ps1.
param([Parameter(Mandatory)][string]$Name, [switch]$Build)
$ports = @{ 'discovery-server' = 8761; 'api-gateway' = 8080; 'identity-service' = 8082; 'ngo-registry-service' = 8083;
  'subscription-service' = 8084; 'donor-service' = 8085; 'donation-payment-service' = 8086; 'receipt-service' = 8087;
  'campaign-project-service' = 8088; 'ngo-operations-service' = 8089; 'helpdesk-service' = 8090;
  'notification-service' = 8091; 'reporting-service' = 8092 }
$c = Get-NetTCPConnection -LocalPort $ports[$Name] -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
if ($c) { Stop-Process -Id $c.OwningProcess -Force; "stopped $Name (pid $($c.OwningProcess))"; Start-Sleep 3 }
if ($Build) {
  $env:MAVEN_OPTS = '-Xmx512m -XX:+UseSerialGC'
  & cmd /c "D:\ngo-platform\scripts\build-backend.cmd -DskipTests -q -o -pl services/$Name -am"
  "build: " + (Get-Content D:\ngo-platform\logs\build-backend.status)
}
& powershell -NoProfile -ExecutionPolicy Bypass -File D:\ngo-platform\scripts\start-local.ps1 -Services $Name
