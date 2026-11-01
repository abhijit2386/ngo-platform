# DEV/TEST ONLY: end-to-end donation receipts (receipt-service) with the standard dataset:
#   admins Asha + Ravi, staff Sunil, finance Priya, donors Meera + Kiran (+ a cash donor without account).
# Settings (prefix, 12A), signatories + signature images, receipts after online and cash donations, numbering per
# financial year, profile name on the receipt (also in Devanagari), e-mail + in-app delivery, donor / guest download,
# QR verification, NGO register + search + summary, document vault entry, reissue, correction (void), immutability.
# Re-runnable: E2E campaign / donations / receipts are cleaned up.
param([string]$NgoId = '0cf945f6-1183-42f5-aeb1-20bab9be302b')
$ErrorActionPreference = 'Stop'
$api = 'http://localhost:8080'; $kc = 'http://localhost:8180/realms/ngo/protocol/openid-connect/token'
$mailpit = 'http://localhost:8025/api/v1'
$psql = 'D:\tools\pgsql\bin\psql.exe'
$env:PGPASSWORD = 'dev-only-password'  # local dev cluster (setup-local.ps1)
$pass = 0; $fail = 0
function Check([string]$name, [bool]$ok, $detail = '') {
  if ($ok) { $script:pass++; "PASS  $name" } else { $script:fail++; "FAIL  $name  $detail" }
}
function Token($user, $pw) {
  (Invoke-RestMethod -Method Post $kc -Body @{ grant_type = 'password'; client_id = 'ngo-admin-cli'; username = $user; password = $pw; scope = 'openid' }).access_token
}
function Api($method, $path, $tok, $body = $null) {
  $h = @{}
  if ($tok) { $h.Authorization = "Bearer $tok" }
  try {
    $a = @{ Method = $method; Uri = "$api$path"; Headers = $h; UseBasicParsing = $true }
    if ($null -ne $body) { $a.ContentType = 'application/json; charset=utf-8'; $a.Body = [Text.Encoding]::UTF8.GetBytes(($body | ConvertTo-Json -Depth 6)) }
    $resp = Invoke-WebRequest @a
    $ms = New-Object IO.MemoryStream; $resp.RawContentStream.CopyTo($ms)
    $text = [Text.Encoding]::UTF8.GetString($ms.ToArray())
    if ([string]::IsNullOrWhiteSpace($text)) { [pscustomobject]@{ __status = [int]$resp.StatusCode } } else { $text | ConvertFrom-Json }
  } catch {
    $status = 0; $text = ''
    if ($_.Exception.Response) {
      $status = [int]$_.Exception.Response.StatusCode
      try { $sr = New-Object IO.StreamReader($_.Exception.Response.GetResponseStream(), [Text.Encoding]::UTF8); $text = $sr.ReadToEnd() } catch { }
    }
    $code = $null; try { $code = ($text | ConvertFrom-Json).code } catch { }
    [pscustomobject]@{ __status = $status; __code = $code; __body = $text }
  }
}
function J($o) { $o | ConvertTo-Json -Compress -Depth 5 }
function WaitFor([scriptblock]$cond, [int]$secs = 60) {
  $end = (Get-Date).AddSeconds($secs)
  while ((Get-Date) -lt $end) { $v = & $cond; if ($v) { return $v }; Start-Sleep 3 }
  return $null
}
function Png([int]$w, [int]$h, [string]$color = 'Wheat') {
  Add-Type -AssemblyName System.Drawing
  $bmp = New-Object Drawing.Bitmap $w, $h
  $g = [Drawing.Graphics]::FromImage($bmp); $g.Clear([Drawing.Color]::FromName($color))
  $g.DrawString('BILL No. 1234   Rs 4,500', (New-Object Drawing.Font('Arial', 28)), [Drawing.Brushes]::Black, 40, 40)
  $g.Dispose()
  $ms = New-Object IO.MemoryStream; $bmp.Save($ms, [Drawing.Imaging.ImageFormat]::Png); $bmp.Dispose()
  return , $ms.ToArray()
}
# multipart: optional JSON part + file parts
function Multi($path, $tok, [string]$jsonName, $json, [string]$fileName, [object[]]$files, [string]$type = 'image/png') {
  Add-Type -AssemblyName System.Net.Http
  $c = New-Object Net.Http.HttpClient
  $c.DefaultRequestHeaders.Authorization = New-Object Net.Http.Headers.AuthenticationHeaderValue('Bearer', $tok)
  $form = New-Object Net.Http.MultipartFormDataContent
  if ($jsonName) {
    $sc = New-Object Net.Http.StringContent(($json | ConvertTo-Json -Depth 6), [Text.Encoding]::UTF8, 'application/json')
    $form.Add($sc, $jsonName)
  }
  $i = 0
  foreach ($f in $files) {
    $part = New-Object Net.Http.ByteArrayContent(, [byte[]]$f)
    $part.Headers.ContentType = [Net.Http.Headers.MediaTypeHeaderValue]::Parse($type)
    $i++; $form.Add($part, $fileName, "bill$i.png")
  }
  $r = $c.PostAsync("$api$path", $form).Result
  $body = $r.Content.ReadAsStringAsync().Result
  $c.Dispose()
  $o = $null; try { $o = $body | ConvertFrom-Json } catch { }
  if ($null -eq $o) { $o = [pscustomobject]@{} }
  $o | Add-Member -NotePropertyName __status -NotePropertyValue ([int]$r.StatusCode) -Force
  $o
}
function Get-Bytes($path, $tok) {
  try {
    $h = @{}; if ($tok) { $h.Authorization = "Bearer $tok" }
    $r = Invoke-WebRequest "$api$path" -Headers $h -UseBasicParsing
    $ms = New-Object IO.MemoryStream; $r.RawContentStream.CopyTo($ms)
    [pscustomobject]@{ status = [int]$r.StatusCode; type = $r.Headers['Content-Type']; bytes = $ms.ToArray(); disp = $r.Headers['Content-Disposition'] }
  } catch { [pscustomobject]@{ status = [int]$_.Exception.Response.StatusCode; bytes = @() } }
}
function Status($path, $tok) {
  try {
    $h = @{}; if ($tok) { $h.Authorization = "Bearer $tok" }
    $r = Invoke-WebRequest "$api$path" -Headers $h -UseBasicParsing
    [pscustomobject]@{ status = [int]$r.StatusCode; type = $r.Headers['Content-Type']; bytes = $r.RawContentLength }
  } catch { [pscustomobject]@{ status = [int]$_.Exception.Response.StatusCode } }
}

function Checkout($tok, $body, $key = [guid]::NewGuid().ToString()) {
  $h = @{ Authorization = "Bearer $tok"; 'Idempotency-Key' = $key }
  try {
    $r = Invoke-WebRequest -Method Post -Uri "$api/api/v1/donations" -Headers $h -UseBasicParsing -ContentType 'application/json; charset=utf-8' `
      -Body ([Text.Encoding]::UTF8.GetBytes(($body | ConvertTo-Json)))
    $ms = New-Object IO.MemoryStream; $r.RawContentStream.CopyTo($ms); [Text.Encoding]::UTF8.GetString($ms.ToArray()) | ConvertFrom-Json
  } catch {
    $text = ''; try { $sr = New-Object IO.StreamReader($_.Exception.Response.GetResponseStream()); $text = $sr.ReadToEnd() } catch { }
    $code = $null; try { $code = ($text | ConvertFrom-Json).code } catch { }
    [pscustomobject]@{ __status = [int]$_.Exception.Response.StatusCode; __code = $code; __body = $text }
  }
}
function Pay($tok, $orderId, $scenario) { Api POST "/api/v1/dev/mock-payments/$orderId/complete" $tok @{ scenario = $scenario } }
function MailTo($addr) { (Invoke-RestMethod "$mailpit/search?query=to:$addr").messages }
function MailMessage($id) {
  $r = Invoke-WebRequest "$mailpit/message/$id" -UseBasicParsing
  $ms = New-Object IO.MemoryStream; $r.RawContentStream.CopyTo($ms)
  [Text.Encoding]::UTF8.GetString($ms.ToArray()) | ConvertFrom-Json
}
function Mine($tok, $donationId) { @((Api GET '/api/v1/receipts/mine' $tok).items | Where-Object { $_.donationId -eq $donationId }) }
function Fy { $d = Get-Date; $s = if ($d.Month -ge 4) { $d.Year } else { $d.Year - 1 }; "$s-" + (($s + 1) % 100).ToString('00') }
function Seq($number) { [int]($number.Split('/')[-1]) }
function SignaturePng {
  Add-Type -AssemblyName System.Drawing
  $bmp = New-Object Drawing.Bitmap 600, 200
  $g = [Drawing.Graphics]::FromImage($bmp); $g.Clear([Drawing.Color]::White)
  $g.SmoothingMode = 'AntiAlias'
  $pen = New-Object Drawing.Pen ([Drawing.Color]::FromArgb(20, 30, 120)), 6
  $g.DrawBezier($pen, 40, 150, 160, 20, 260, 190, 380, 60); $g.DrawBezier($pen, 380, 60, 450, 20, 520, 160, 570, 90)
  $g.Dispose()
  $ms = New-Object IO.MemoryStream; $bmp.Save($ms, [Drawing.Imaging.ImageFormat]::Png); $bmp.Dispose()
  return , $ms.ToArray()
}

& powershell -NoProfile -ExecutionPolicy Bypass -File "$PSScriptRoot\cleanup-e2e-donations.ps1" | Out-Null
& $psql -w -h localhost -U postgres -d campaign -q -f "$PSScriptRoot\sql\cleanup-e2e-campaigns.sql" | Out-Null

$asha = Token 'asha.admin@ngo.test' 'DevNgo#2026'
$ravi = Token 'ravi.staff@ngo.test' 'DevStaff#2026'
$sunil = Token 'sunil.staff@ngo.test' 'DevTest#2026'
$priya = Token 'priya.admin@ngo.test' 'DevTest#2026'
$meera = Token 'meera.donor@ngo.test' 'DevDonor#2026'
$kiran = Token 'kiran.donor@ngo.test' 'DevTest#2026'
Check 'tokens issued (2 admins, staff, finance, 2 donors)' ($asha -and $ravi -and $sunil -and $priya -and $meera -and $kiran)
$rb = "/api/v1/ngos/$NgoId/receipts"
$fy = Fy

# --- settings ---
$st = Api GET "$rb/settings" $sunil
Check 'the team sees receipt settings (prefix from the NGO name until saved)' ($st.prefix -match '^[A-Z0-9]{2,10}$') (J $st)
$x = Api PUT "$rb/settings" $sunil @{ prefix = 'ASF'; version = $st.version }
Check 'staff cannot change receipt settings (403)' ($x.__status -eq 403) (J $x)
$x = Api PUT "$rb/settings" $asha @{ prefix = 'AS/F'; version = $st.version }
Check 'prefix with a slash refused' ($x.__code -eq 'INVALID_PREFIX') (J $x)
$st = Api PUT "$rb/settings" $asha @{ prefix = 'asf'; reg12aNumber = 'AAATA1234BE20214'; thankYouNote = 'Thank you for standing with the children of Wadi.'; version = $st.version }
Check 'Asha sets prefix ASF (upper-cased), 12A number and thank-you line' ($st.prefix -eq 'ASF' -and $st.reg12aNumber -eq 'AAATA1234BE20214' -and $st.saved) (J $st)
$x = Api PUT "$rb/settings" $asha @{ prefix = 'ASF'; version = ($st.version + 5) }
Check 'stale settings save refused (409)' ($x.__status -eq 409) (J $x)

# --- signatories ---
foreach ($s in @((Api GET "$rb/signatories" $asha) | ForEach-Object { $_ })) { if ($s.id) { $null = Api DELETE "$rb/signatories/$($s.id)" $asha } }
$x = Api POST "$rb/signatories" $sunil @{ name = 'Sunil More'; designation = 'Staff' }
Check 'staff cannot add signatories (403)' ($x.__status -eq 403) (J $x)
$chair = Api POST "$rb/signatories" $asha @{ name = 'Asha Patil'; designation = 'Chairman' }
$sec = Api POST "$rb/signatories" $asha @{ name = 'Ravi Kulkarni'; designation = 'Secretary' }
$tre = Api POST "$rb/signatories" $asha @{ name = 'Priya Deshmukh'; designation = 'Treasurer' }
Check 'Asha adds Chairman, Secretary, Treasurer' ($chair.id -and $sec.id -and $tre.id -and $tre.position -eq 2) (J @($chair, $sec, $tre))
$x = Api POST "$rb/signatories" $asha @{ name = 'Fourth Person'; designation = 'Member' }
Check 'a receipt has room for 3 signatories' ($x.__code -eq 'TOO_MANY_SIGNATORIES') (J $x)
$sig = Multi "$rb/signatories/$($chair.id)/signature" $asha $null $null 'file' @(, (SignaturePng)) 'image/png'
Check 'Chairman signature uploaded (PNG)' ($sig.__status -eq 200 -and $sig.hasSignature) (J $sig)
$x = Multi "$rb/signatories/$($sec.id)/signature" $asha $null $null 'file' @(, [Text.Encoding]::ASCII.GetBytes("%PDF-1.4`n%fake`n")) 'application/pdf'
Check 'PDF signature refused (PNG / JPEG only)' ($x.code -eq 'IMAGE_TYPE_NOT_ALLOWED') (J $x)
$x = Multi "$rb/signatories/$($sec.id)/signature" $asha $null $null 'file' @(, (New-Object byte[] 1200000)) 'image/png'
Check 'signature over 1 MB refused' ($x.code -eq 'FILE_TOO_LARGE') (J $x)
$img = Get-Bytes "$rb/signatories/$($chair.id)/signature" $asha
Check 'admins see the signature image (transparent PNG)' ($img.status -eq 200 -and $img.type -like 'image/png*') "$($img.status)"
$img = Get-Bytes "$rb/signatories/$($chair.id)/signature" $sunil
Check 'staff cannot download signature images (403)' ($img.status -eq 403) "$($img.status)"
$null = Api DELETE "$rb/signatories/$($tre.id)" $asha
Check 'treasurer removed: 2 signatories left' (@((Api GET "$rb/signatories" $sunil) | ForEach-Object { $_ }).Count -eq 2) ''
$smp = Get-Bytes "$rb/sample" $asha
Check 'sample receipt with these settings (PDF)' ($smp.status -eq 200 -and [Text.Encoding]::ASCII.GetString($smp.bytes, 0, 5) -eq '%PDF-') "$($smp.status)"
[IO.File]::WriteAllBytes("$PSScriptRoot\..\.agent\e2e-receipt-sample.pdf", $smp.bytes)

# --- campaign + online donations ---
$base = "/api/v1/ngos/$NgoId/campaigns"
$camp = Api POST $base $asha @{ title = 'E2E Receipts test campaign'; summary = 'Used by the receipt end-to-end test only.'; cause = 'EDUCATION'; goalAmount = '50000'; startDate = (Get-Date).ToString('yyyy-MM-dd'); endDate = (Get-Date).AddDays(20).ToString('yyyy-MM-dd') }
$camp = Api POST "$base/$($camp.id)/approve" $asha @{ version = $camp.version }
Check 'test campaign is live' ($camp.status -eq 'PUBLISHED') (J $camp)
$meeraMe = Api GET '/api/v1/me' $meera
$g = @{ ngoId = $NgoId; campaignId = $camp.id; amount = '2500'; donorName = 'Somebody Else'; donorEmail = 'meera.donor@ngo.test'; wants80g = $true; donorPan = 'ABCDE1234F'; donorAddress = '12 MG Road, Pune 411001' }
$co = Checkout $meera $g
$p = Pay $meera $co.orderId 'SUCCESS'
Check 'Meera pays Rs 2,500 with 80G' ($p.status -eq 'PAID') (J $p)
$r1 = @(WaitFor { $m = @(Mine $meera $co.donationId); if ($m.Count -gt 0 -and $m[0].receiptNumber) { $m } } 180)[0]
Check "receipt issued within 3 minutes: ASF/$fy/nnnnnn" ($r1.receiptNumber -match "^ASF/$fy/\d{6}$") (J @{ want = $co.donationId; have = @((Api GET '/api/v1/receipts/mine' $meera).items | ForEach-Object { "$($_.donationId) $($_.receiptNumber)" }); co = $co })
Check 'receipt shows the name from Meera''s profile, not what the app sent' ($r1.donorName -eq $meeraMe.displayName -and $r1.donorName -ne 'Somebody Else') "$($r1.donorName) vs $($meeraMe.displayName)"
Check 'receipt is an 80G receipt for Rs 2,500' ($r1.tax80g -and [decimal]$r1.amount -eq 2500) (J $r1)
$pdf = Get-Bytes "/api/v1/receipts/$($r1.id)/pdf" $meera
Check 'Meera downloads her receipt (PDF)' ($pdf.status -eq 200 -and [Text.Encoding]::ASCII.GetString($pdf.bytes, 0, 5) -eq '%PDF-') "$($pdf.status)"
[IO.File]::WriteAllBytes("$PSScriptRoot\..\.agent\e2e-receipt-meera.pdf", $pdf.bytes)
$x = Get-Bytes "/api/v1/receipts/$($r1.id)/pdf" $kiran
Check 'Kiran cannot open Meera''s receipt (404)' ($x.status -eq 404) "$($x.status)"
$inapp = @(WaitFor { @((Api GET '/api/v1/notifications?size=50' $meera).items | Where-Object { $_.templateCode -eq 'RECEIPT_ISSUED' -and $_.link -eq "/donor/receipts/$($r1.id)" }) } 120)
Check 'Meera is told in the app (link to the receipt)' ($inapp.Count -ge 1) ''
$mail = @(WaitFor { @(MailTo 'meera.donor@ngo.test' | Where-Object { $_.Subject -like "*$($r1.receiptNumber)*" }) } 120)
Check 'Meera gets an e-mail with the receipt number' ($mail.Count -ge 1) ''
$link = $null
if ($mail.Count -ge 1) { $body = (MailMessage $mail[0].ID).Text; $m = [regex]::Match($body, 'http\S+/api/v1/public/receipts/\S+'); if ($m.Success) { $link = $m.Value } }
$guest = if ($link) { Get-Bytes ($link.Substring($link.IndexOf('/api/v1/'))) $null } else { $null }
Check 'the e-mail link downloads the receipt without signing in' ($guest -and $guest.status -eq 200 -and $guest.disp -like 'attachment*') (J $link)
$bad = if ($link) { Get-Bytes (($link.Substring($link.IndexOf('/api/v1/'))) -replace 'sig=[^&]+', 'sig=forged') $null } else { $null }
Check 'a tampered e-mail link is refused (403)' ($bad -and $bad.status -eq 403) ''
$del = WaitFor { $m = @(Mine $meera $co.donationId); if ($m[0].status -eq 'DELIVERED') { $m[0] } } 60
Check 'receipt marked DELIVERED after the e-mail was sent' ($null -ne $del) ''

# Kiran: profile name in Devanagari is printed as written (owner decision 2026-11-01).
$kMe = Api GET '/api/v1/me' $kiran
$prof = @{ displayName = 'किरण राव'; phone = ($kMe.phone -replace '^\+91', ''); preferredLanguage = $kMe.preferredLanguage; birthDate = $kMe.birthDate; gender = $kMe.gender; confirmAdult = $true }
$u = Api PUT '/api/v1/me/profile' $kiran $prof
Check 'Kiran renames himself in his profile (Devanagari)' ($u.displayName -eq 'किरण राव') (J $u)
$co2 = Checkout $kiran @{ ngoId = $NgoId; campaignId = $camp.id; amount = '750'; donorName = 'Kiran'; donorEmail = 'kiran.donor@ngo.test'; wants80g = $false }
$null = Pay $kiran $co2.orderId 'SUCCESS'
$r2 = @(WaitFor { $m = @(Mine $kiran $co2.donationId); if ($m.Count -gt 0 -and $m[0].receiptNumber) { $m } } 120)[0]
Check 'Kiran''s receipt carries his profile name in Devanagari' ($r2.donorName -eq 'किरण राव') "$($r2.donorName)"
Check 'numbers are consecutive in the financial year' ((Seq $r2.receiptNumber) -eq (Seq $r1.receiptNumber) + 1) "$($r1.receiptNumber) -> $($r2.receiptNumber)"
Check 'no 80G on Kiran''s receipt (not asked)' (-not $r2.tax80g) (J $r2)
$k2 = Get-Bytes "/api/v1/receipts/$($r2.id)/pdf" $kiran
[IO.File]::WriteAllBytes("$PSScriptRoot\..\.agent\e2e-receipt-kiran.pdf", $k2.bytes)
$prof.displayName = $kMe.displayName
$null = Api PUT '/api/v1/me/profile' $kiran $prof

# --- QR verification ---
$v = Api GET "/api/v1/public/receipts/verify/$($r1.verifyCode)?format=json" $null
Check 'QR check: genuine, number + amount, donor initials only' ($v.found -and $v.status -eq 'VALID' -and $v.receiptNumber -eq $r1.receiptNumber -and $v.donor -like 'M*`*`*`**' -and $v.donor -notlike '*eera*') (J $v)
$html = Status "/api/v1/public/receipts/verify/$($r1.verifyCode)" $null
Check 'QR check page opens in a browser (HTML)' ($html.status -eq 200 -and $html.type -like 'text/html*') (J $html)
$v = Api GET '/api/v1/public/receipts/verify/ZZZZZZZZZZ?format=json' $null
Check 'unknown code: not found' (-not $v.found) (J $v)

# --- cash donation recorded by staff (no account, no e-mail) ---
$cash = Api POST "/api/v1/ngos/$NgoId/donations/offline" $asha @{ donorName = 'E2E Cash Donor'; amount = '1001'; channel = 'CASH'; receivedOn = (Get-Date).ToString('yyyy-MM-dd'); purpose = 'E2E cash at the office'; wants80g = $false }
Check 'Asha records a Rs 1,001 cash donation' ($null -ne $cash.id) (J $cash)
$r3 = @(WaitFor { $l = @((Api GET "$rb/by-donation/$($cash.id)" $asha) | ForEach-Object { $_ } | Where-Object { $_.status -in 'GENERATED', 'DELIVERY_PENDING', 'DELIVERED' }); if ($l.Count -gt 0) { $l } } 120)[0]
Check 'cash receipt issued with the name as typed, kept by the NGO (no e-mail)' ($r3.donorName -eq 'E2E Cash Donor' -and $r3.status -eq 'GENERATED' -and $r3.channel -eq 'CASH') (J $r3)
$x = Api POST "$rb/$($r3.id)/resend" $asha
Check 'cannot e-mail a donor without an address' ($x.__code -eq 'NO_RECIPIENT') (J $x)

# --- NGO register ---
$l = Api GET "${rb}?q=$([uri]::EscapeDataString($r1.receiptNumber))" $sunil
Check 'staff search the register by receipt number' (@($l.items).Count -eq 1 -and $l.items[0].id -eq $r1.id) (J $l.totalItems)
$l = Api GET "${rb}?q=cash%20donor&status=ISSUED" $sunil
Check 'search by donor name + status' (@($l.items | Where-Object { $_.id -eq $r3.id }).Count -eq 1) (J $l.totalItems)
$sum = Api GET "$rb/summary" $sunil
Check "summary for FY $fy (issued count + amount, prefix)" ($sum.fiscalYear -eq $fy -and [int]$sum.issued -ge 3 -and [decimal]$sum.issuedAmount -ge 4251 -and $sum.prefix -eq 'ASF') (J $sum)
$x = Api GET $rb $meera
Check 'donors cannot open the NGO register (403)' ($x.__status -eq 403) (J $x)
$d1 = Api GET "$rb/$($r1.id)" $priya
Check 'detail with history (created, numbered, generated, delivery)' (@($d1.history | Where-Object { $_.event -in 'NUMBERED', 'GENERATED', 'DELIVERY_REQUESTED' }).Count -ge 3) (J ($d1.history | ForEach-Object { $_.event }))
$vault = WaitFor { $p = Api GET "/api/v1/ngos/$NgoId/library?category=RECEIPT&q=$([uri]::EscapeDataString($r1.receiptNumber))" $asha; if (@($p.items).Count -gt 0) { $p } } 60
Check 'the receipt is listed in Documents (Receipts)' ($vault -and $vault.items[0].source -eq 'RECEIPT' -and $vault.items[0].linkType -eq 'RECEIPT') (J $vault.items)

# --- reissue, correction, immutability ---
$x = Api POST "$rb/$($r3.id)/reissue" $sunil @{ reason = 'Name spelt wrong' }
Check 'staff cannot reissue (403)' ($x.__status -eq 403) (J $x)
$x = Api POST "$rb/$($r3.id)/reissue" $asha @{ reason = 'typo' }
Check 'reissue needs a real reason' ($x.__status -eq 400) (J $x)
$n3 = Api POST "$rb/$($r3.id)/reissue" $asha @{ reason = 'Donor asked for the receipt in the trust name' }
$n3 = WaitFor { $y = Api GET "$rb/$($n3.id)" $asha; if ($y.receipt.status -in 'GENERATED', 'DELIVERY_PENDING', 'DELIVERED') { $y } } 60
$o3 = Api GET "$rb/$($r3.id)" $asha
Check 'reissue: old receipt cancelled, new number, both linked' ($o3.receipt.status -eq 'REISSUED' -and $n3.receipt.receiptNumber -ne $r3.receiptNumber -and $n3.replaces.id -eq $r3.id -and $o3.replacedBy.id -eq $n3.receipt.id) (J @{ old = $o3.receipt.status; new = $n3.receipt.receiptNumber })
$v = Api GET "/api/v1/public/receipts/verify/$($r3.verifyCode)?format=json" $null
Check 'QR check of the old receipt says CANCELLED' ($v.found -and $v.status -eq 'CANCELLED') (J $v)
$cv = WaitFor { $p = Api GET "/api/v1/ngos/$NgoId/library?category=RECEIPT&q=$([uri]::EscapeDataString($r3.receiptNumber))" $asha; if (@($p.items | Where-Object { $_.title -like 'Cancelled:*' }).Count -gt 0) { $p } } 60
Check 'Documents shows the old receipt as cancelled' ($null -ne $cv) ''
$fix = Api POST "/api/v1/ngos/$NgoId/donations/$($cash.id)/correction" $asha @{ reason = 'E2E wrong amount'; corrected = @{ donorName = 'E2E Cash Donor'; amount = '1100'; channel = 'CASH'; receivedOn = (Get-Date).ToString('yyyy-MM-dd'); purpose = 'E2E cash at the office'; wants80g = $false } }
$vo = WaitFor { $y = Api GET "$rb/$($n3.receipt.id)" $asha; if ($y.receipt.status -eq 'VOIDED') { $y } } 60
Check 'donation corrected: its receipt is cancelled automatically' ($null -ne $vo) (J $fix.id)
$r4 = @(WaitFor { $l = @((Api GET "$rb/by-donation/$($fix.id)" $asha) | ForEach-Object { $_ } | Where-Object { $_.status -in 'GENERATED', 'DELIVERY_PENDING', 'DELIVERED' }); if ($l.Count -gt 0) { $l } } 120)[0]
Check 'the corrected donation gets its own receipt (Rs 1,100)' ($r4 -and [decimal]$r4.amount -eq 1100) (J $r4)
$err = try { (& $psql -w -h localhost -U postgres -d receipt -c "update donation_receipts set amount = 1 where id = '$($r1.id)'" 2>&1 | Out-String) } catch { $_.Exception.Message }
Check 'database refuses to change an issued receipt' ($err -match 'immutable') $err
$err = try { (& $psql -w -h localhost -U postgres -d receipt -c "delete from donation_receipts where id = '$($r1.id)'" 2>&1 | Out-String) } catch { $_.Exception.Message }
Check 'database refuses to delete a receipt' ($err -match 'ERROR') $err

# --- R2: custom receipt design (Basic designer, Professional plan) ---
function Jpeg([int]$w, [int]$h) {
  Add-Type -AssemblyName System.Drawing
  $bmp = New-Object Drawing.Bitmap $w, $h
  $g = [Drawing.Graphics]::FromImage($bmp); $g.Clear([Drawing.Color]::FromArgb(255, 248, 232))
  $g.FillRectangle((New-Object Drawing.SolidBrush ([Drawing.Color]::FromArgb(140, 70, 20))), 0, 0, $w, [int]($h * 0.14))
  $g.DrawString('ASHA SEVA FOUNDATION - DONATION RECEIPT', (New-Object Drawing.Font('Arial', [int]($h / 30))), [Drawing.Brushes]::White, 40, 30)
  $pen = New-Object Drawing.Pen ([Drawing.Color]::FromArgb(140, 70, 20)), 3
  foreach ($y in 0.35, 0.45, 0.55, 0.65) { $g.DrawLine($pen, [int]($w * 0.25), [int]($h * $y), [int]($w * 0.95), [int]($h * $y)) }
  $g.Dispose()
  $ms = New-Object IO.MemoryStream; $bmp.Save($ms, [Drawing.Imaging.ImageFormat]::Jpeg); $bmp.Dispose()
  return , $ms.ToArray()
}
$tb = "/api/v1/ngos/$NgoId/receipt-templates"
foreach ($old in @((Api GET $tb $asha) | ForEach-Object { $_ } | Where-Object { $_.id })) { $null = Api POST "$tb/$($old.id)/deactivate" $asha; $null = Api POST "$tb/$($old.id)/archive" $asha }
$lv = Api GET "$tb/designer" $sunil
Check 'Professional trial includes the Basic designer' ($lv.level -eq 'BASIC') (J $lv)
$x = Api POST $tb $sunil @{ name = 'E2E design' }
Check 'staff cannot create receipt designs (403)' ($x.__status -eq 403) (J $x)
$tp = Api POST $tb $asha @{ name = 'E2E design' }
Check 'Asha creates a design with the default field layout' ($tp.id -and @($tp.definition.fields).Count -ge 8 -and $tp.kind -eq 'BASIC') (J $tp.kind)
$x = Api POST $tb $asha @{ name = 'E2E second design' }
Check 'Basic plan: one design only' ($x.__status -ge 400) (J $x)
$x = Multi "$tb/$($tp.id)/background" $asha $null $null 'file' @(, [Text.Encoding]::ASCII.GetBytes("%PDF-1.4`n%fake receipt`n")) 'application/pdf'
Check 'PDF background refused (PNG / JPEG only)' ($x.code -eq 'IMAGE_TYPE_NOT_ALLOWED') (J $x)
$x = Multi "$tb/$($tp.id)/background" $asha $null $null 'file' @(, (Jpeg 900 640)) 'image/jpeg'
Check 'blurry background refused (under 1240 px)' ($x.code -eq 'IMAGE_TOO_SMALL') (J $x)
$tp = Multi "$tb/$($tp.id)/background" $asha $null $null 'file' @(, (Jpeg 1754 1240)) 'image/jpeg'
Check 'receipt-book picture uploaded: page follows its shape (A5 landscape)' ($tp.hasBackground -and [double]$tp.definition.page.height -gt 418 -and [double]$tp.definition.page.height -lt 423) (J $tp.definition.page)
$def = $tp.definition
$f = @($def.fields | Where-Object { $_.key -eq 'donorName' })[0]; $f.x = 150; $f.y = 150
$tp = Api PUT "$tb/$($tp.id)" $asha @{ definition = $def; version = $tp.version }
Check 'Asha moves the donor name and saves (new version)' ($tp.currentVersion -ge 3) (J $tp.currentVersion)
$val = Api GET "$tb/$($tp.id)/validation" $asha
Check 'design passes the checks' (@($val.errors).Count -eq 0) (J $val)
$pv = Get-Bytes "$tb/$($tp.id)/preview" $asha
Check 'preview PDF with a sample donor' ($pv.status -eq 200 -and [Text.Encoding]::ASCII.GetString($pv.bytes, 0, 5) -eq '%PDF-') "$($pv.status)"
[IO.File]::WriteAllBytes("$PSScriptRoot\..\.agent\e2e-receipt-design-preview.pdf", $pv.bytes)
$tp = Api POST "$tb/$($tp.id)/submit" $asha
Check 'Asha publishes: design in use' ($tp.status -eq 'PUBLISHED' -and $tp.active) (J @{ s = $tp.status; a = $tp.active })
$co3 = Checkout $meera @{ ngoId = $NgoId; campaignId = $camp.id; amount = '1500'; donorName = 'Meera'; donorEmail = 'meera.donor@ngo.test'; wants80g = $false }
$null = Pay $meera $co3.orderId 'SUCCESS'
$r5 = @(WaitFor { $m = @(Mine $meera $co3.donationId); if ($m.Count -gt 0 -and $m[0].receiptNumber) { $m } } 120)[0]
Check 'the next receipt is printed on the NGO''s design' ($r5.templateId -eq $tp.id -and $r5.templateVersion -eq $tp.publishedVersion) (J $r5)
$p5 = Get-Bytes "/api/v1/receipts/$($r5.id)/pdf" $meera
[IO.File]::WriteAllBytes("$PSScriptRoot\..\.agent\e2e-receipt-design-meera.pdf", $p5.bytes)
$tp = Api POST "$tb/$($tp.id)/deactivate" $asha
Check '"use standard receipt" switches the design off' (-not $tp.active) (J $tp.active)
$null = Api POST "$tb/$($tp.id)/archive" $asha

# --- earlier donations got receipts (backfill) ---
$all = Api GET "${rb}?status=ISSUED&size=100" $asha
Check 'donations made before receipts existed have receipts too' (@($all.items | Where-Object { $_.campaignTitle -notlike 'E2E *' -and $_.donorName -notlike 'E2E *' }).Count -ge 1) (J $all.totalItems)

# cleanup
& powershell -NoProfile -ExecutionPolicy Bypass -File "$PSScriptRoot\cleanup-e2e-donations.ps1" | Out-Null
$null = Api POST "$base/$($camp.id)/close" $asha
& $psql -w -h localhost -U postgres -d campaign -q -f "$PSScriptRoot\sql\cleanup-e2e-campaigns.sql" | Out-Null
$left = & $psql -w -h localhost -U postgres -d receipt -t -A -c "select count(*) from donation_receipts where donor_name like 'E2E %'"
Check 'E2E data cleaned up' ([int]$left -eq 0) "$left"

""
"RESULT: $pass passed, $fail failed"
if ($fail -gt 0) { exit 1 }
