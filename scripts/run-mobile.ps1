# Builds the debug APK, installs and launches it on the running emulator. Each step runs with stdin from NUL,
# output to a file, and a timeout (a Windows console quirk can otherwise hang dart tools).
param([switch]$Analyze, [switch]$Test, [switch]$SkipBuild, [ValidateSet('debug','profile')][string]$Mode = 'debug',
  # -Cloud: the app talks to the OCI server (https://api.yogdaan.co.in, https://auth.yogdaan.co.in), HTTPS only,
  # no test-payment screen. Default: the laptop stack through adb reverse.
  [switch]$Cloud, [string]$ApiUrl = 'https://api.yogdaan.co.in', [string]$AuthUrl = 'https://auth.yogdaan.co.in/realms/ngo')
$ErrorActionPreference = 'Continue'
$Repo = 'D:\ngo-platform'
$env:PATH = "D:\tools\flutter\bin;D:\tools\android-sdk\platform-tools;D:\tools\git\cmd;$env:PATH"
$env:JAVA_HOME = (Get-ChildItem D:\tools -Directory -Filter 'jdk-21*' | Select-Object -First 1).FullName
$env:ANDROID_HOME = 'D:\tools\android-sdk'
$log = "$Repo\logs\run-mobile.log"
Set-Content "$Repo\logs\run-mobile.status" 'RUNNING'
Set-Content $log "[$(Get-Date -Format s)] run-mobile"
Set-Location "$Repo\apps\mobile"

function Step($name, $cmdline, $timeoutMin) {
  Write-Host "=== $name" -ForegroundColor Green
  Add-Content $log "=== $name"
  $out = "$Repo\logs\step-$name.log"
  $p = Start-Process -FilePath cmd.exe -ArgumentList "/c $cmdline < NUL > `"$out`" 2>&1" -NoNewWindow -PassThru
  $null = $p.Handle  # cache the handle so ExitCode is available after exit
  if (-not $p.WaitForExit($timeoutMin * 60 * 1000)) {
    Write-Host "TIMEOUT after $timeoutMin min" -ForegroundColor Red
    & taskkill /PID $p.Id /T /F | Out-Null
    Add-Content $log "TIMEOUT"
    return 124
  }
  Get-Content $out | Select-Object -Last 60 | ForEach-Object { Write-Host $_ }
  Get-Content $out | Add-Content $log
  Add-Content $log "exit=$($p.ExitCode)"
  return $p.ExitCode
}

$status = 'OK'
$gradleZip = 'D:\tools\_dl\gradle-9.3.1-bin.zip'
New-Item -ItemType Directory -Force -Path 'D:\tools\_dl' | Out-Null
if (-not (Test-Path $gradleZip)) {
  Write-Host '=== downloading Gradle 9.3.1' -ForegroundColor Green
  & curl.exe -L --fail --retry 3 -o "$gradleZip.part" https://services.gradle.org/distributions/gradle-9.3.1-bin.zip
  if ($LASTEXITCODE -eq 0) { Move-Item "$gradleZip.part" $gradleZip -Force } else { Write-Host 'Gradle download failed' -ForegroundColor Red }
}
$env:JAVA_TOOL_OPTIONS = '-Djava.net.preferIPv4Stack=true'
Step 'pubget' 'flutter pub get' 10 | Out-Null
Step 'l10n' 'flutter gen-l10n' 5 | Out-Null
if ($Analyze) { if ((Step 'analyze' 'flutter analyze --no-fatal-infos' 20) -ne 0) { $status = 'ANALYZE_FAIL' } }
if ($Test) { if ((Step 'test' 'flutter test' 20) -ne 0 -and $status -eq 'OK') { $status = 'TEST_FAIL' } }
$apk = "$Repo\apps\mobile\build\app\outputs\flutter-apk\app-$Mode.apk"
# Skip the (slow) build when the APK is newer than every source file.
if (-not $SkipBuild -and (Test-Path $apk)) {
  $newest = Get-ChildItem "$Repo\apps\mobile\lib", "$Repo\apps\mobile\pubspec.yaml", "$Repo\apps\mobile\android\app" -Recurse -File | Where-Object { $_.Name -notlike 'app_localizations*.dart' } | Sort-Object LastWriteTime -Descending | Select-Object -First 1
  if ((Get-Item $apk).LastWriteTime -gt $newest.LastWriteTime) { $SkipBuild = $true; Add-Content $log '=== build skipped (APK up to date)' }
}
if (-not $SkipBuild) {
  $before = if (Test-Path $apk) { (Get-Item $apk).LastWriteTime } else { [datetime]::MinValue }
  $defines = if ($Cloud) { " --dart-define=API_BASE_URL=$ApiUrl --dart-define=OIDC_ISSUER=$AuthUrl --dart-define=ALLOW_INSECURE=$(if ($ApiUrl -like 'http:*') { 'true' } else { 'false' }) --dart-define=MOCK_PAYMENTS=false" } else { '' }
  Step 'build' "flutter build apk --$Mode$defines" 60 | Out-Null
  if (-not (Test-Path $apk) -or (Get-Item $apk).LastWriteTime -le $before) { Set-Content "$Repo\logs\run-mobile.status" 'BUILD_FAIL'; exit 1 }
}
Set-Content "$Repo\logs\run-mobile.status" 'BUILT'
# Start the emulator only after the build (this PC cannot hold Gradle and the emulator in RAM together).
# The emulator's commit grows from ~4.3 GB at boot to 16.5 GB after a day (measured 2026-11-02), which starved the
# backend JVMs: restart it once it passes 8 GB. The app is reinstalled below anyway.
$qemu = Get-Process qemu-system-x86_64 -ErrorAction SilentlyContinue
if ($qemu -and $qemu.PrivateMemorySize64 -gt 8GB) {
  Add-Content $log "=== restarting emulator (commit $([int]($qemu.PrivateMemorySize64/1MB)) MB)"
  & adb emu kill | Out-Null
  for ($i = 0; $i -lt 30 -and (Get-Process qemu-system-x86_64 -ErrorAction SilentlyContinue); $i++) { Start-Sleep 2 }
}
$devices = (& adb devices) -match 'emulator-\d+\s+device'
if (-not $devices) {
  Add-Content $log '=== starting emulator'
  Start-Process -FilePath 'D:\tools\android-sdk\emulator\emulator.exe' -ArgumentList '-avd ngo_pixel -memory 2048 -netdelay none -netspeed full -no-snapshot-save -gpu swiftshader_indirect -no-boot-anim'
  & adb wait-for-device
  for ($i = 0; $i -lt 90; $i++) {
    if ((& adb shell getprop sys.boot_completed 2>$null) -match '1') { break }
    Start-Sleep -Seconds 5
  }
  Add-Content $log "emulator boot wait: $($i*5)s"
}
# Emulator's localhost:8080 (API) and :8180 (Keycloak) -> this machine.
& adb reverse tcp:8080 tcp:8080 | Out-Null
& adb reverse tcp:8180 tcp:8180 | Out-Null
Step 'install' "adb install -r build\app\outputs\flutter-apk\app-$Mode.apk" 5 | Out-Null
Step 'launch' 'adb shell am start -n org.ngoplatform.app/org.ngoplatform.ngo_app.MainActivity' 2 | Out-Null
Set-Content "$Repo\logs\run-mobile.status" $status
Write-Host "=== done: $status" -ForegroundColor Green
