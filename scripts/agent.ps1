# Local helper for remote development sessions: runs command files dropped into D:\ngo-platform\.agent
# (cmd-*.ps1, oldest first), writes output to out-<name>.txt and moves the command to done\.
# Close this window to stop it.
$dir = 'D:\ngo-platform\.agent'
New-Item -ItemType Directory -Force -Path $dir, "$dir\done" | Out-Null
$host.UI.RawUI.WindowTitle = 'ngo-platform agent (close to stop)'
Write-Host "Watching $dir for cmd-*.ps1 ... close this window to stop." -ForegroundColor Green
while ($true) {
  Get-ChildItem $dir -Filter 'cmd-*.ps1' | Sort-Object Name | ForEach-Object {
    $name = $_.BaseName
    Write-Host "[$(Get-Date -Format T)] running $name" -ForegroundColor Cyan
    Set-Content "$dir\out-$name.txt" "RUNNING"
    try {
      # File redirection instead of a pipe: servers started by a command must not inherit (and hold) our pipe.
      $p = Start-Process -FilePath powershell.exe -ArgumentList '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', "`"$($_.FullName)`"" `
            -RedirectStandardOutput "$dir\out-$name.log" -RedirectStandardError "$dir\err-$name.log" -WindowStyle Hidden -PassThru
      $p.WaitForExit()
      $o = (Get-Content "$dir\out-$name.log" -Raw) + "`r`n" + (Get-Content "$dir\err-$name.log" -Raw)
      Set-Content "$dir\out-$name.txt" ($o + "`r`n### EXIT $($p.ExitCode)")
    } catch { Set-Content "$dir\out-$name.txt" ("### ERROR " + $_) }
    Move-Item $_.FullName "$dir\done\" -Force
  }
  Start-Sleep -Seconds 2
}
