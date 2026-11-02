# DEV/TEST ONLY: makes Asha Seva able to take online donations in development (run after seed-dataset.ps1).
# - 80G registration marked verified (fictional number) so donors can ask for 80G receipts
# - online payments switched on with the TEST payment provider (money never moves)
# Idempotent.
param([string]$NgoId = '0cf945f6-1183-42f5-aeb1-20bab9be302b')
$ErrorActionPreference = 'Stop'
$api = 'http://localhost:8080'; $kc = 'http://localhost:8180/realms/ngo/protocol/openid-connect/token'
function Token($user, $pw) {
  (Invoke-RestMethod -Method Post $kc -Body @{ grant_type = 'password'; client_id = 'ngo-admin-cli'; username = $user; password = $pw; scope = 'openid' }).access_token
}
function Api($method, $path, $tok, $body = $null) {
  $a = @{ Method = $method; Uri = "$api$path"; Headers = @{ Authorization = "Bearer $tok" }; UseBasicParsing = $true }
  if ($null -ne $body) { $a.ContentType = 'application/json; charset=utf-8'; $a.Body = [Text.Encoding]::UTF8.GetBytes(($body | ConvertTo-Json -Depth 6)) }
  $r = Invoke-WebRequest @a
  $ms = New-Object IO.MemoryStream; $r.RawContentStream.CopyTo($ms)
  $t = [Text.Encoding]::UTF8.GetString($ms.ToArray()); if ($t) { $t | ConvertFrom-Json }
}
function Cover([string]$from, [string]$to) {
  Add-Type -AssemblyName System.Drawing
  $bmp = New-Object Drawing.Bitmap 1600, 900
  $g = [Drawing.Graphics]::FromImage($bmp)
  $g.SmoothingMode = 'AntiAlias'
  $rect = New-Object Drawing.Rectangle 0, 0, 1600, 900
  $brush = New-Object Drawing.Drawing2D.LinearGradientBrush($rect, [Drawing.ColorTranslator]::FromHtml($from), [Drawing.ColorTranslator]::FromHtml($to), 35)
  $g.FillRectangle($brush, $rect)
  $soft = New-Object Drawing.SolidBrush([Drawing.Color]::FromArgb(40, 255, 255, 255))
  foreach ($c in @(@(1250, -150, 600), @(-200, 500, 650), @(900, 600, 350))) { $g.FillEllipse($soft, $c[0], $c[1], $c[2], $c[2]) }
  $g.Dispose()
  $ms = New-Object IO.MemoryStream; $bmp.Save($ms, [Drawing.Imaging.ImageFormat]::Png); $bmp.Dispose()
  return , $ms.ToArray()
}
function Upload($path, $tok, [byte[]]$bytes) {
  Add-Type -AssemblyName System.Net.Http
  $c = New-Object Net.Http.HttpClient
  $c.DefaultRequestHeaders.Authorization = New-Object Net.Http.Headers.AuthenticationHeaderValue('Bearer', $tok)
  $form = New-Object Net.Http.MultipartFormDataContent
  $part = New-Object Net.Http.ByteArrayContent(, $bytes)
  $part.Headers.ContentType = [Net.Http.Headers.MediaTypeHeaderValue]::Parse('image/png')
  $form.Add($part, 'file', 'cover.png')
  $r = $c.PostAsync("$api$path", $form).Result; $c.Dispose()
  if (-not $r.IsSuccessStatusCode) { throw "cover upload failed: $([int]$r.StatusCode)" }
}

$env:PGPASSWORD = 'dev-only-password'
& 'D:\tools\pgsql\bin\psql.exe' -h localhost -U postgres -d ngo_registry -q -c "update ngos set tax_80g_verified = true, tax_80g_number = coalesce(tax_80g_number, 'AAATA0000A25PN01-TEST'), tax_80g_valid_until = '2031-03-31' where id = '$NgoId';" | Out-Null
"80G: verified (test number), valid until 31 Mar 2031"
$asha = Token 'asha.admin@ngo.test' 'DevNgo#2026'
$ps = Api GET "/api/v1/ngos/$NgoId/payment-settings" $asha
if ($ps.status -ne 'ACTIVE') {
  $ps = Api PUT "/api/v1/ngos/$NgoId/payment-settings" $asha @{ provider = 'MOCK'; merchantReference = 'mock_ashaseva'; guestCheckoutAllowed = $true; minAmount = '10'; maxAmount = '1000000'; receiptPrefix = 'ASHA' }
}
"payments: $($ps.provider) $($ps.status)"

# Offline donations recorded by staff (reports: "My report", cash in the 80G register / Form 10BD data).
# Skipped when a donation with the same reference exists (re-runnable). DEV DATA ONLY: names and PANs are made up.
$sunil = Token 'sunil.staff@ngo.test' 'DevTest#2026'
$priya = Token 'priya.admin@ngo.test' 'DevTest#2026'
$books = [string](@(& 'D:\tools\pgsql\bin\psql.exe' -w -h localhost -U postgres -d campaign -tAc "select id from campaigns where ngo_id = '$NgoId' and title like 'School books%' limit 1") | Select-Object -First 1)
$offline = @(
  @{ who = $sunil; by = 'Sunil'; ref = 'SEED-CASH-001'; body = @{ donorName = 'Shanta Kale'; donorPan = 'ABCPK1234L'; amount = '1500.00'; channel = 'CASH'; wants80g = $true; purpose = 'General donation' } },
  @{ who = $sunil; by = 'Sunil'; ref = 'SEED-CASH-002'; body = @{ donorName = 'Ganesh Pawar'; donorPan = 'BGHPP4321Q'; amount = '2500.00'; channel = 'CASH'; wants80g = $true; purpose = 'General donation' } },
  @{ who = $priya; by = 'Priya'; ref = 'SEED-CHQ-001'; body = @{ donorName = 'Pune Traders LLP'; donorPan = 'AAJFP1234K'; amount = '5000.00'; channel = 'CHEQUE'; wants80g = $true; campaignId = $books } }
)
foreach ($o in $offline) {
  $exists = [string](@(& 'D:\tools\pgsql\bin\psql.exe' -w -h localhost -U postgres -d donation -tAc "select count(*) from donations where ngo_id = '$NgoId' and offline_reference = '$($o.ref)' and not voided") | Select-Object -First 1)
  if ($exists -ne '0') { "offline $($o.ref): already there"; continue }
  $b = $o.body; $b.reference = $o.ref; $b.receivedOn = (Get-Date).ToString('yyyy-MM-dd')
  if (-not $b.campaignId) { $b.Remove('campaignId') }
  $r = Api POST "/api/v1/ngos/$NgoId/donations/offline" $o.who $b
  "offline $($o.ref): $($b.channel) $($b.amount) by $($o.by) -> $($r.status)$($r.__status)"
}
