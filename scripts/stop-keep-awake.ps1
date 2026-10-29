# Ends keep-awake.ps1 (normal sleep/lock behaviour returns immediately).
$f = 'D:\ngo-platform\logs\keep-awake.pid'
if (Test-Path $f) { Stop-Process -Id ([int](Get-Content $f)) -ErrorAction SilentlyContinue; Remove-Item $f; 'keep-awake stopped' } else { 'keep-awake was not running' }
