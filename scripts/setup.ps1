# Developer toolchain setup for ngo-platform (Windows x64).
# Portable installs into D:\tools; sets USER environment variables only.
param([switch]$SkipPodman)
$ErrorActionPreference = 'Continue'  # native tools write to stderr; failures are checked explicitly
$ProgressPreference = 'SilentlyContinue'
$Tools = 'D:\tools'
$Repo  = 'D:\ngo-platform'
$Logs  = "$Repo\logs"
New-Item -ItemType Directory -Force -Path $Tools, $Logs, "$Tools\_dl" | Out-Null
Start-Transcript -Path "$Logs\setup.log" -Append | Out-Null
function Step($m) { Write-Host "`n=== $m ===" -ForegroundColor Green; Add-Content "$Logs\progress.txt" "$(Get-Date -Format s) START $m" }
function Done($m) { Add-Content "$Logs\progress.txt" "$(Get-Date -Format s) DONE  $m" }
function Fail($m) { Add-Content "$Logs\progress.txt" "$(Get-Date -Format s) FAIL  $m" }
function Get-File($url, $out) {
  if (Test-Path $out) { Write-Host "cached $out"; return }
  Write-Host "downloading $url"
  & curl.exe -L --fail --retry 3 -o "$out.part" $url
  if ($LASTEXITCODE -ne 0) { throw "download failed: $url" }
  Move-Item "$out.part" $out -Force
}
function Expand-To($zip, $dest) { Write-Host "extracting $zip"; & tar.exe -xf $zip -C $dest; if ($LASTEXITCODE -ne 0) { Expand-Archive $zip $dest -Force } }
function Add-UserPath($p) {
  $cur = [Environment]::GetEnvironmentVariable('Path','User'); if (-not $cur) { $cur = '' }
  if (($cur -split ';') -notcontains $p) { [Environment]::SetEnvironmentVariable('Path', ($cur.TrimEnd(';') + ';' + $p).TrimStart(';'), 'User') }
  if (($env:Path -split ';') -notcontains $p) { $env:Path = "$p;$env:Path" }
}
function Set-UserVar($n,$v) { [Environment]::SetEnvironmentVariable($n,$v,'User'); Set-Item "env:$n" $v }

"Machine: $env:COMPUTERNAME  OS: $([Environment]::OSVersion.VersionString)"
Get-PSDrive D | Format-Table Name,@{n='FreeGB';e={[math]::Round($_.Free/1GB,1)}} | Out-String | Write-Host

# ---------- Git (MinGit, portable) ----------
Step 'git'
try {
  if (Get-Command git -ErrorAction SilentlyContinue) { git --version }
  else {
    $rel = Invoke-RestMethod 'https://api.github.com/repos/git-for-windows/git/releases/latest'
    $asset = $rel.assets | Where-Object { $_.name -match '^MinGit-[\d.]+-64-bit\.zip$' } | Select-Object -First 1
    Get-File $asset.browser_download_url "$Tools\_dl\$($asset.name)"
    New-Item -ItemType Directory -Force "$Tools\git" | Out-Null
    Expand-To "$Tools\_dl\$($asset.name)" "$Tools\git"
    Add-UserPath "$Tools\git\cmd"; git --version
  }
  Done 'git'
} catch { Fail "git: $_"; Write-Warning $_ }

# ---------- JDK 21 (Eclipse Temurin) ----------
Step 'jdk21'
try {
  $jdk = Get-ChildItem $Tools -Directory -Filter 'jdk-21*' -ErrorAction SilentlyContinue | Select-Object -First 1
  if (-not $jdk) {
    Get-File 'https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jdk/hotspot/normal/eclipse?project=jdk' "$Tools\_dl\temurin21.zip"
    Expand-To "$Tools\_dl\temurin21.zip" $Tools
    $jdk = Get-ChildItem $Tools -Directory -Filter 'jdk-21*' | Select-Object -First 1
  }
  Set-UserVar 'JAVA_HOME' $jdk.FullName; Add-UserPath "$($jdk.FullName)\bin"
  & "$($jdk.FullName)\bin\java.exe" -version 2>&1 | Write-Host
  Done 'jdk21'
} catch { Fail "jdk21: $_"; Write-Warning $_ }

# ---------- Maven ----------
Step 'maven'
try {
  $mv = '3.9.9'
  if (-not (Test-Path "$Tools\apache-maven-$mv")) {
    Get-File "https://archive.apache.org/dist/maven/maven-3/$mv/binaries/apache-maven-$mv-bin.zip" "$Tools\_dl\maven.zip"
    Expand-To "$Tools\_dl\maven.zip" $Tools
  }
  Set-UserVar 'MAVEN_HOME' "$Tools\apache-maven-$mv"; Add-UserPath "$Tools\apache-maven-$mv\bin"
  & "$Tools\apache-maven-$mv\bin\mvn.cmd" -v | Write-Host
  Done 'maven'
} catch { Fail "maven: $_"; Write-Warning $_ }

# ---------- Flutter stable ----------
Step 'flutter'
try {
  if (-not (Test-Path "$Tools\flutter\bin\flutter.bat")) {
    $base = 'https://storage.googleapis.com/flutter_infra_release/releases'
    $idx = Invoke-RestMethod "$base/releases_windows.json"
    $hash = $idx.current_release.stable
    $r = $idx.releases | Where-Object { $_.hash -eq $hash -and $_.channel -eq 'stable' } | Select-Object -First 1
    Write-Host "Flutter stable $($r.version)"
    Get-File "$base/$($r.archive)" "$Tools\_dl\flutter.zip"
    Expand-To "$Tools\_dl\flutter.zip" $Tools
  }
  Add-UserPath "$Tools\flutter\bin"
  & "$Tools\flutter\bin\flutter.bat" config --no-analytics | Out-Null
  & "$Tools\flutter\bin\flutter.bat" --disable-analytics 2>&1 | Out-Null
  & "$Tools\flutter\bin\flutter.bat" --version 2>&1 | Write-Host
  Done 'flutter'
} catch { Fail "flutter: $_"; Write-Warning $_ }

# ---------- Android SDK + emulator ----------
Step 'android-sdk'
try {
  $sdk = "$Tools\android-sdk"
  $cmd = "$sdk\cmdline-tools\latest\bin"
  if (-not (Test-Path "$cmd\sdkmanager.bat")) {
    Get-File 'https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip' "$Tools\_dl\cmdline-tools.zip"
    New-Item -ItemType Directory -Force "$sdk\cmdline-tools" | Out-Null
    Expand-To "$Tools\_dl\cmdline-tools.zip" "$sdk\cmdline-tools"
    if (Test-Path "$sdk\cmdline-tools\latest") { Remove-Item "$sdk\cmdline-tools\latest" -Recurse -Force }
    Rename-Item "$sdk\cmdline-tools\cmdline-tools" 'latest'
  }
  Set-UserVar 'ANDROID_HOME' $sdk; Set-UserVar 'ANDROID_SDK_ROOT' $sdk
  Add-UserPath "$sdk\platform-tools"; Add-UserPath "$sdk\emulator"; Add-UserPath $cmd
  # Licenses accepted by the user in chat on 2026-10-28.
  $yes = ('y' + "`n") * 40
  $yes | & "$cmd\sdkmanager.bat" --licenses 2>&1 | Select-Object -Last 3 | Write-Host
  $pkgs = 'platform-tools','emulator','platforms;android-36','platforms;android-35','build-tools;36.0.0','build-tools;35.0.0','build-tools;28.0.3','system-images;android-35;google_apis;x86_64'
  $yes | & "$cmd\sdkmanager.bat" --install @pkgs 2>&1 | Where-Object { $_ -notmatch '^\[=*\s*\]' } | Write-Host
  if (-not ((& "$cmd\avdmanager.bat" list avd 2>&1) -match 'ngo_pixel')) {
    'no' | & "$cmd\avdmanager.bat" create avd -n ngo_pixel -k 'system-images;android-35;google_apis;x86_64' -d pixel_7 2>&1 | Write-Host
  }
  & "$cmd\avdmanager.bat" list avd 2>&1 | Write-Host
  & "$sdk\emulator\emulator.exe" -accel-check 2>&1 | Write-Host
  & "$Tools\flutter\bin\flutter.bat" config --android-sdk $sdk 2>&1 | Write-Host
  Done 'android-sdk'
} catch { Fail "android-sdk: $_"; Write-Warning $_ }

# ---------- Flutter doctor ----------
Step 'flutter-doctor'
try {
  $yes = ('y' + "`n") * 20
  $yes | & "$Tools\flutter\bin\flutter.bat" doctor --android-licenses 2>&1 | Select-Object -Last 2 | Write-Host
  & "$Tools\flutter\bin\flutter.bat" doctor -v 2>&1 | Tee-Object "$Logs\flutter-doctor.txt" | Write-Host
  Done 'flutter-doctor'
} catch { Fail "flutter-doctor: $_"; Write-Warning $_ }

# ---------- Podman (needs admin + WSL2) ----------
if (-not $SkipPodman) {
  Step 'podman'
  try {
    if (-not (Get-Command podman -ErrorAction SilentlyContinue) -and -not (Test-Path "$env:ProgramFiles\RedHat\Podman\podman.exe")) {
      winget install --id RedHat.Podman -e --silent --accept-package-agreements --accept-source-agreements 2>&1 | Write-Host
    }
    $pm = (Get-Command podman -ErrorAction SilentlyContinue).Source; if (-not $pm) { $pm = "$env:ProgramFiles\RedHat\Podman\podman.exe" }
    & $pm --version
    wsl --status 2>&1 | Write-Host
    $machines = & $pm machine list --format '{{.Name}}' 2>&1
    if (-not $machines) { & $pm machine init --cpus 4 --memory 6144 2>&1 | Write-Host }
    $running = & $pm machine list --format '{{.Running}}' 2>&1
    if (-not ($running -match 'true')) { & $pm machine start 2>&1 | Write-Host }
    & $pm info --format '{{.Host.OS}} {{.Version.Version}}' 2>&1 | Write-Host
    Done 'podman'
  } catch { Fail "podman: $_"; Write-Warning $_ }
}

Add-Content "$Logs\progress.txt" "$(Get-Date -Format s) ALL-FINISHED"
Stop-Transcript | Out-Null
