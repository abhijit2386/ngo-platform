param([switch]$Quiet)
$Repo = 'D:\ngo-platform'
if (Test-Path "$Repo\logs\backend.pids") {
  foreach ($line in Get-Content "$Repo\logs\backend.pids") {
    $id = [int]($line.Split(' ')[0])
    $p = Get-Process -Id $id -ErrorAction SilentlyContinue
    if ($p -and $p.ProcessName -eq 'java') { Stop-Process -Id $id -Force; if (-not $Quiet) { Write-Host "stopped $line" } }
  }
  Remove-Item "$Repo\logs\backend.pids" -Force
}
