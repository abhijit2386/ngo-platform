# UI-test helper for the emulator (dot-source in command files).
$adb = 'D:\tools\android-sdk\platform-tools\adb.exe'
$shots = 'D:\ngo-platform\.agent'
function Tap($x, $y) { & $adb shell input tap $x $y; Start-Sleep -Milliseconds 1500 }
function TypeText([string]$t) { $e = $t.Replace(' ', '%s').Replace('&', '\&').Replace('(', '\(').Replace(')', '\)').Replace("'", "\'"); & $adb shell input text "$e"; Start-Sleep -Milliseconds 800 }
function Key($k) { & $adb shell input keyevent $k; Start-Sleep -Milliseconds 800 }
function Swipe($x1, $y1, $x2, $y2) { & $adb shell input swipe $x1 $y1 $x2 $y2 400; Start-Sleep -Milliseconds 1200 }
function Shot($name) { & cmd /c "`"$adb`" exec-out screencap -p > $shots\$name.png" }
# uiautomator waits for the app to go idle and can hang forever (endless animation, ANR): give it 25 s.
function Ui() {
  $p = Start-Process -FilePath $adb -ArgumentList 'shell', 'uiautomator', 'dump', '/sdcard/ui.xml' -WindowStyle Hidden -PassThru
  if (-not $p.WaitForExit(25000)) {
    try { $p.Kill() } catch {}
    & $adb shell pkill -f uiautomator 2>&1 | Out-Null
    Write-Output '<!-- UI DUMP TIMEOUT -->'; return
  }
  & $adb shell cat /sdcard/ui.xml
}

# Finds a node whose text / content-desc / hint starts with $label (case-insensitive) and returns its centre.
function Find([string]$label, [int]$nth = 0) {
  $xml = Ui
  $ms = [regex]::Matches($xml, '<node [^>]*?(?:text|content-desc|hint)="([^"]+)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
  $hits = @($ms | Where-Object { $_.Groups[1].Value -ceq $label })
  if ($hits.Count -le $nth) { $hits = @($ms | Where-Object { $_.Groups[1].Value -clike "$label*" }) }
  if ($hits.Count -le $nth) { Write-Output "NOT FOUND: $label"; return $null }
  $m = $hits[$nth]
  return @([int](([int]$m.Groups[2].Value + [int]$m.Groups[4].Value) / 2), [int](([int]$m.Groups[3].Value + [int]$m.Groups[5].Value) / 2))
}
function TapText([string]$label, [int]$nth = 0) {
  $p = Find $label $nth
  if ($p -is [array] -and $p.Count -eq 2) { "tap '$label' at $($p[0]),$($p[1])"; Tap $p[0] $p[1] } else { $p }
}
# Lists visible labels with bounds (for planning the next step).
function Labels() {
  $xml = Ui
  [regex]::Matches($xml, '<node [^>]*?class="([^"]*)"[^>]*?(?:text|content-desc)="([^"]+)"[^>]*?bounds="([^"]+)"') |
    ForEach-Object { "{0} | {1} | {2}" -f $_.Groups[2].Value.Substring(0, [Math]::Min(60, $_.Groups[2].Value.Length)).Replace("`n", ' '), $_.Groups[3].Value, $_.Groups[1].Value.Split('.')[-1] }
}
function EditTexts() {
  $xml = Ui
  [regex]::Matches($xml, '<node [^>]*?class="android.widget.EditText"[^>]*?bounds="([^"]+)"') | ForEach-Object { "EditText " + $_.Groups[1].Value }
}

# Polls the screen until a label appears (returns $true) or the timeout passes.
function WaitFor([string]$label, [int]$seconds = 60) {
  $end = (Get-Date).AddSeconds($seconds)
  while ((Get-Date) -lt $end) {
    $xml = Ui
    if ($xml -match [regex]::Escape($label)) { "found '$label'"; return }
    Start-Sleep 3
  }
  "TIMEOUT waiting for '$label'"
}
