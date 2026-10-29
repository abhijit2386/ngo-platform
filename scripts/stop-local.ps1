# Stops everything start-local.ps1 started (services, Keycloak, Mailpit, PostgreSQL).
$Repo = 'D:\ngo-platform'; $Tools = 'D:\tools'
$pidFile = "$Repo\logs\backend.pids"
if (Test-Path $pidFile) {
  Get-Content $pidFile | ForEach-Object { $id = ($_ -split ' ')[0]; & taskkill /PID $id /T /F 2>$null | Out-Null }
  Remove-Item $pidFile
}
# Keycloak runs as java.exe under kc.bat: stop by its command line.
Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -like '*keycloak*' } |
  ForEach-Object { & taskkill /PID $_.ProcessId /T /F | Out-Null }
Get-Process mailpit -ErrorAction SilentlyContinue | Stop-Process -Force
& "$Tools\pgsql\bin\pg_ctl.exe" -D "$Repo\.local\pgdata" stop -m fast 2>&1 | Out-Null
Write-Host 'local stack stopped'
