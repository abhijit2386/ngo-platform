# Deploys Yogdaan to the OCI server over SSH (Oracle Linux 8/9, x86_64). Runs on the laptop through the runner.
#   scripts\deploy-cloud.ps1 -Server 1.2.3.4 -Steps all                      first install, every component
#   scripts\deploy-cloud.ps1 -Server 1.2.3.4 -Steps services                  update all services
#   scripts\deploy-cloud.ps1 -Server 1.2.3.4 -Steps services -Only receipt-service,reporting-service
#   scripts\deploy-cloud.ps1 -Server 1.2.3.4 -Steps smoke                     checks only
#   scripts\deploy-cloud.ps1 -Server 1.2.3.4 -Steps services -Rollback        previous jars back
# Sends the jars built and tested on this laptop (services\<svc>\target\app.jar: run build-backend.cmd first), the
# install kit (deploy\oci), config\yogdaan.env.prod and the realm file. The SSH key stays on this laptop.
# Nothing is deleted on the server: each upload goes to /opt/yogdaan-kit/<time> and 'current' points to it.
param([Parameter(Mandatory = $true)][string]$Server,
  [string]$User = 'opc',
  [string]$Key = "$env:USERPROFILE\.ssh\yogdaan_oci",
  [string[]]$Steps = @('all'),
  [string[]]$Only = @(),
  [switch]$Rollback,
  # builds the kit only (no SSH): to check the packing before the server exists
  [switch]$PackOnly)
# native tools (ssh, scp, tar) write progress to stderr: errors are judged by exit codes, not by stderr
$ErrorActionPreference = 'Continue'
$Repo = 'D:\ngo-platform'
$ssh = "$env:WINDIR\System32\OpenSSH\ssh.exe"; $scp = "$env:WINDIR\System32\OpenSSH\scp.exe"
$log = "$Repo\logs\deploy-cloud.log"
function Log($m) { $line = "[$(Get-Date -Format 'HH:mm:ss')] $m"; $line; Add-Content $log $line }
Set-Content $log "deploy-cloud $(Get-Date -Format s) server=$Server steps=$($Steps -join ',') only=$($Only -join ',')"

$services = 'discovery-server','identity-service','ngo-registry-service','subscription-service','notification-service',
  'campaign-project-service','donation-payment-service','receipt-service','document-service','reporting-service','api-gateway'
$envProd = "$Repo\config\yogdaan.env.prod"
if ($PackOnly -and -not (Test-Path $envProd)) { $envProd = "$Repo\config\yogdaan.env.prod.example" }  # packing test only
$must = @($envProd); if (-not $PackOnly) { $must += @($ssh, $scp, $Key) }
foreach ($f in $must) { if (-not (Test-Path $f)) { throw "missing: $f" } }
foreach ($s in $Only) { if ($services -notcontains $s) { throw "unknown service: $s" } }

# ---- bundle
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$work = "$Repo\.deploy\kit-$stamp"
New-Item -ItemType Directory -Force "$work\files\jars" | Out-Null
Copy-Item "$Repo\deploy\oci\*" $work -Recurse -Force
Get-ChildItem $work -Recurse -Include *.sh | ForEach-Object {     # bash needs LF line endings
  $t = [IO.File]::ReadAllText($_.FullName); [IO.File]::WriteAllText($_.FullName, $t.Replace("`r`n", "`n"))
}
$envText = [IO.File]::ReadAllText($envProd).Replace("`r`n", "`n")
[IO.File]::WriteAllText("$work\files\yogdaan.env.prod", $envText)
Copy-Item "$Repo\infrastructure\keycloak\ngo-realm.json" "$work\files\ngo-realm.json"
$needJars = ($Steps -contains 'all' -or $Steps -contains 'services') -and -not $Rollback
if ($needJars) {
  foreach ($s in $services) {
    if ($Only.Count -gt 0 -and $Only -notcontains $s) { continue }
    $jar = "$Repo\services\$s\target\app.jar"
    if (-not (Test-Path $jar)) { throw "$s not built: run scripts\build-backend.cmd first" }
    Copy-Item $jar "$work\files\jars\$s.jar"
    Log ("jar {0,-26} {1:N1} MB  built {2}" -f $s, ((Get-Item $jar).Length / 1MB), (Get-Item $jar).LastWriteTime.ToString('dd MMM HH:mm'))
  }
}
$tgz = "$Repo\.deploy\yogdaan-kit-$stamp.tgz"
& tar -czf $tgz -C $work . 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0 -or -not (Test-Path $tgz)) { throw 'packing the kit failed' }
Log ("kit {0:N1} MB" -f ((Get-Item $tgz).Length / 1MB))
if ($PackOnly) { Log "PACK ONLY: $tgz"; return }

# ---- upload and run
$opts = @('-i', $Key, '-o', 'StrictHostKeyChecking=accept-new', '-o', 'BatchMode=yes', '-o', 'ServerAliveInterval=30', '-o', 'ConnectTimeout=20')
& $scp @opts $tgz "${User}@${Server}:/tmp/yogdaan-kit-$stamp.tgz" 2>&1 | ForEach-Object { Log "$_" }
if ($LASTEXITCODE -ne 0) { throw "upload failed (check the IP, port 22 open for this laptop's IP, the key)" }
$vars = @()
if ($Only.Count -gt 0) { $vars += "ONLY='$($Only -join ' ')'" }
if ($Rollback) { $vars += 'ROLLBACK=1' }
$remote = "set -e; d=/opt/yogdaan-kit/$stamp; sudo mkdir -p `$d; sudo tar -xzf /tmp/yogdaan-kit-$stamp.tgz -C `$d; rm -f /tmp/yogdaan-kit-$stamp.tgz; " +
  "sudo ln -sfn `$d /opt/yogdaan-kit/current; sudo chmod -R go-rwx /opt/yogdaan-kit; sudo $($vars -join ' ') bash `$d/install.sh $($Steps -join ' ')"
Log "running on server: install.sh $($Steps -join ' ') $($vars -join ' ')"
& $ssh -n @opts "${User}@${Server}" $remote  # -n: the server never waits for input from this laptop 2>&1 | ForEach-Object { Log "$_" }
$code = $LASTEXITCODE
Log "server exit code: $code"
if ($code -ne 0) { throw "install failed: see $log" }
Log 'DEPLOY OK'
