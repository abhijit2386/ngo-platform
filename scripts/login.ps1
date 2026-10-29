# Dev/test-only helper: sign in on the local Keycloak page (localhost:8180) with fictional test users.
param([string]$Email, [string]$Pass, [string]$Tag)
. D:\ngo-platform\scripts\ui.ps1
function SlowType([string]$s) { foreach ($c in $s.ToCharArray()) { $e = "$c"; if ($e -eq '#') { $e = '\#' }; & $adb shell input text "$e"; Start-Sleep -Milliseconds 450 } }
function ClearField($x, $y) { Tap $x $y; Start-Sleep 2; Key 123; & $adb shell input keyevent $((1..35 | ForEach-Object { 67 }) -join ' '); Start-Sleep 2 }
TapText 'Sign in'
Start-Sleep 40
ClearField 540 744
SlowType $Email
Start-Sleep 3
ClearField 500 929
SlowType $Pass
Start-Sleep 3
Tap 965 929
Start-Sleep 3
Shot "$Tag-form"
Tap 540 1186
Start-Sleep 60
Shot "$Tag-after"
Labels
