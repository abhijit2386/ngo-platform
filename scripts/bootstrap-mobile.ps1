# Generates Flutter platform scaffolding (once) and resolves packages. Output is shown AND logged.
$ErrorActionPreference = 'Continue'
$Repo = 'D:\ngo-platform'
$env:PATH = "D:\tools\flutter\bin;D:\tools\git\cmd;$env:PATH"
$jdk = Get-ChildItem D:\tools -Directory -Filter 'jdk-21*' | Select-Object -First 1
$env:JAVA_HOME = $jdk.FullName
$env:ANDROID_HOME = 'D:\tools\android-sdk'
Set-Content "$Repo\logs\bootstrap-mobile.status" 'RUNNING'
Start-Transcript -Path "$Repo\logs\bootstrap-mobile.log" -Force | Out-Null
Set-Location "$Repo\apps"
if (-not (Test-Path 'mobile\android')) {
  flutter create --org org.ngoplatform --project-name ngo_app --platforms android,ios --no-pub mobile
}
Set-Location "$Repo\apps\mobile"
$ok = $true
foreach ($p in @('flutter_riverpod','go_router','dio','flutter_secure_storage','flutter_appauth','intl','url_launcher',
                 'shared_preferences','path_provider','open_filex','file_picker','share_plus')) {
  Write-Host "--- pub add $p" -ForegroundColor Green
  flutter pub add $p --no-precompile 2>&1 | Write-Host
  if ($LASTEXITCODE -ne 0) { $ok = $false; Write-Host "FAILED: $p" -ForegroundColor Red }
}
flutter pub add flutter_localizations --sdk=flutter 2>&1 | Write-Host
flutter pub add dev:mocktail 2>&1 | Write-Host
flutter pub get 2>&1 | Write-Host
if ($LASTEXITCODE -ne 0) { $ok = $false }
Write-Host '=== pubspec.yaml'; Get-Content pubspec.yaml | Write-Host
Write-Host '=== resolved'; flutter pub deps --style=compact 2>&1 | Select-String -Pattern '^- ' | Write-Host
Write-Host '=== gradle'; Get-Content android\app\build.gradle.kts | Write-Host
Stop-Transcript | Out-Null
Set-Content "$Repo\logs\bootstrap-mobile.status" ($(if ($ok) { 'OK' } else { 'FAIL' }))
