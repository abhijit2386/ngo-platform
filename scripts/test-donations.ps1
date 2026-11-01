# DEV/TEST ONLY: end-to-end online donation through the gateway with the TEST payment provider (no real money),
# using the standard dataset: donors Meera + Kiran give, admins Asha + Ravi are told, staff Sunil may not donate.
# Needs: seed-dataset.ps1, seed-campaigns.ps1, seed-donations.ps1. Re-runnable (own "E2E" campaign, cleaned up).
param([string]$NgoId = '0cf945f6-1183-42f5-aeb1-20bab9be302b')
$ErrorActionPreference = 'Stop'
$api = 'http://localhost:8080'; $kc = 'http://localhost:8180/realms/ngo/protocol/openid-connect/token'
$mailpit = 'http://localhost:8025/api/v1'
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
    $args2 = @{ Method = $method; Uri = "$api$path"; Headers = $h; UseBasicParsing = $true }
    if ($null -ne $body) { $args2.ContentType = 'application/json; charset=utf-8'; $args2.Body = [Text.Encoding]::UTF8.GetBytes(($body | ConvertTo-Json -Depth 6)) }
    $resp = Invoke-WebRequest @args2
    $ms = New-Object IO.MemoryStream; $resp.RawContentStream.CopyTo($ms)
    $text = [Text.Encoding]::UTF8.GetString($ms.ToArray())
    if ([string]::IsNullOrWhiteSpace($text)) { $null } else { $text | ConvertFrom-Json }
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
function Inbox($tok, $template, $campaignId) {
  $r = Api GET '/api/v1/notifications?size=50' $tok
  @($r.items | Where-Object { $_.templateCode -eq $template -and $_.link -eq "/ngo/campaigns/$campaignId" })
}
function MailText($id) {
  $r = Invoke-WebRequest "$mailpit/message/$id" -UseBasicParsing
  $ms = New-Object IO.MemoryStream; $r.RawContentStream.CopyTo($ms)
  [Text.Encoding]::UTF8.GetString($ms.ToArray()) | ConvertFrom-Json
}
function Png([int]$w, [int]$h) {
  Add-Type -AssemblyName System.Drawing
  $bmp = New-Object Drawing.Bitmap $w, $h
  $g = [Drawing.Graphics]::FromImage($bmp); $g.Clear([Drawing.Color]::SeaGreen); $g.Dispose()
  $ms = New-Object IO.MemoryStream; $bmp.Save($ms, [Drawing.Imaging.ImageFormat]::Png); $bmp.Dispose()
  return $ms.ToArray()
}
function Upload($path, $tok, [byte[]]$bytes) {
  Add-Type -AssemblyName System.Net.Http
  $c = New-Object Net.Http.HttpClient
  $c.DefaultRequestHeaders.Authorization = New-Object Net.Http.Headers.AuthenticationHeaderValue('Bearer', $tok)
  $form = New-Object Net.Http.MultipartFormDataContent
  $part = New-Object Net.Http.ByteArrayContent(, $bytes)
  $part.Headers.ContentType = [Net.Http.Headers.MediaTypeHeaderValue]::Parse('image/png')
  $form.Add($part, 'file', 'cover.png')
  $r = $c.PostAsync("$api$path", $form).Result
  $body = $r.Content.ReadAsStringAsync().Result
  $c.Dispose()
  [pscustomobject]@{ status = [int]$r.StatusCode; body = $body }
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
function Raised($cid) { $c = Api GET "/api/v1/public/campaigns/$cid" $null; [decimal]$c.raisedAmount }

$asha = Token 'asha.admin@ngo.test' 'DevNgo#2026'
$ravi = Token 'ravi.staff@ngo.test' 'DevStaff#2026'
$sunil = Token 'sunil.staff@ngo.test' 'DevTest#2026'
$meera = Token 'meera.donor@ngo.test' 'DevDonor#2026'
$kiran = Token 'kiran.donor@ngo.test' 'DevTest#2026'
Check 'tokens issued (admins, staff, 2 donors)' ($asha -and $ravi -and $sunil -and $meera -and $kiran)
$ps = Api GET "/api/v1/ngos/$NgoId/payment-settings" $asha
Check 'Asha Seva takes online donations (test provider)' ($ps.status -eq 'ACTIVE' -and $ps.provider -eq 'MOCK') (J $ps)

# Own live campaign for this test.
$env:PGPASSWORD = 'dev-only-password'
& powershell -NoProfile -ExecutionPolicy Bypass -File "$PSScriptRoot\cleanup-e2e-donations.ps1" | Out-Null
& 'D:\tools\pgsql\bin\psql.exe' -w -h localhost -U postgres -d campaign -q -f "$PSScriptRoot\sql\cleanup-e2e-campaigns.sql" | Out-Null
$base = "/api/v1/ngos/$NgoId/campaigns"
$camp = Api POST $base $asha @{ title = 'E2E Donation test campaign'; summary = 'Used by the donation end-to-end test only.'; cause = 'EDUCATION'; goalAmount = '20000'; startDate = (Get-Date).ToString('yyyy-MM-dd'); endDate = (Get-Date).AddDays(20).ToString('yyyy-MM-dd') }
$camp = Api POST "$base/$($camp.id)/approve" $asha @{ version = $camp.version }
$cid = $camp.id
Check 'test campaign is live' ($camp.status -eq 'PUBLISHED') (J $camp)
$draft = @((Api GET "${base}?status=DRAFT&size=50" $asha).items)[0]
$pending = @((Api GET "${base}?status=PENDING_APPROVAL&size=50" $asha).items)[0]

$gift = @{ ngoId = $NgoId; campaignId = $cid; amount = '500'; donorName = 'Meera Iyer'; donorEmail = 'meera.donor@ngo.test'; wants80g = $false }

# --- who may donate ---
$x = Checkout $sunil $gift
Check 'staff (Sunil) cannot donate to own NGO' ($x.__code -eq 'TEAM_MEMBER_DONATION') (J $x)
$x = Checkout $asha $gift
Check 'admin (Asha) cannot donate to own NGO' ($x.__code -eq 'TEAM_MEMBER_DONATION') (J $x)

# --- validation ---
$g = $gift.Clone(); $g.amount = '5'
$x = Checkout $meera $g; Check 'amount below Rs 10 refused' ($x.__code -eq 'INVALID_AMOUNT' -or $x.__status -eq 400) (J $x)
if ($draft) { $g = $gift.Clone(); $g.campaignId = $draft.id; $x = Checkout $meera $g; Check 'draft campaign refuses donations' ($x.__code -eq 'CAMPAIGN_NOT_LIVE') (J $x) }
if ($pending) { $g = $gift.Clone(); $g.campaignId = $pending.id; $x = Checkout $meera $g; Check 'campaign awaiting approval refuses donations' ($x.__code -eq 'CAMPAIGN_NOT_LIVE') (J $x) }
$g = $gift.Clone(); $g.wants80g = $true
$x = Checkout $meera $g; Check '80G without PAN refused' ($x.__code -eq 'PAN_REQUIRED_FOR_80G') (J $x)
$g.donorPan = 'ABCDE1234F'
$x = Checkout $meera $g; Check '80G without address refused' ($x.__code -eq 'ADDRESS_REQUIRED_FOR_80G') (J $x)
$g.donorPan = 'ABC123'
$g.donorAddress = '12 MG Road, Pune 411001'
$x = Checkout $meera $g; Check 'malformed PAN refused' ($x.__status -eq 400) (J $x)

# --- happy path ---
$before = Raised $cid
$key = [guid]::NewGuid().ToString()
$co = Checkout $meera $gift $key
Check 'Meera starts a Rs 500 donation (test checkout)' ($co.status -eq 'CREATED' -and $co.mockCheckout -eq $true -and $co.orderId) (J $co)
$again = Checkout $meera $gift $key
Check 'same request retried -> same donation (idempotent)' ($again.donationId -eq $co.donationId) (J $again)
$paid = Pay $meera $co.orderId 'SUCCESS'
Check 'payment succeeds -> PAID' ($paid.status -eq 'PAID') (J $paid)
$after = WaitFor { $r = Raised $cid; if ($r -eq $before + 500) { $r } } 60
Check 'campaign raised +Rs 500' ($after -eq $before + 500) "before=$before after=$(Raised $cid)"
$mine = @((Api GET '/api/v1/donations/mine' $meera).items)
$m0 = @($mine | Where-Object { $_.id -eq $co.donationId })[0]
Check 'Meera sees it in her history with NGO + campaign' ($m0.status -eq 'PAID' -and $m0.ngoName -eq 'Asha Seva' -and $m0.campaignTitle -eq 'E2E Donation test campaign') (J $m0)
$n = @(WaitFor { $r = Api GET '/api/v1/notifications?size=30' $meera; @($r.items | Where-Object { $_.templateCode -eq 'DONATION_THANKS' -and $_.body -like '*E2E Donation test*' }) } 60)
Check 'Meera thanked in-app' ($n.Count -ge 1) (J $n)
$mail = @(WaitFor { @((Invoke-RestMethod "$mailpit/search?query=to:meera.donor@ngo.test").messages | Where-Object { $_.Subject -like 'Thank you*' }) } 60)
Check 'Meera gets a thank-you e-mail' ($mail.Count -ge 1)
$an = @(WaitFor { $r = Api GET '/api/v1/notifications?size=30' $asha; @($r.items | Where-Object { $_.templateCode -eq 'DONATION_RECEIVED' -and $_.link -eq "/ngo/campaigns/$cid" }) } 60)
Check 'Asha (admin) told about the new donation' ($an.Count -ge 1) (J $an)
Check 'admin note has no donor name' ($an.Count -ge 1 -and -not ($an[0].body -like '*Meera*')) (J $an)
$rn = @(WaitFor { $r = Api GET '/api/v1/notifications?size=30' $ravi; @($r.items | Where-Object { $_.templateCode -eq 'DONATION_RECEIVED' -and $_.link -eq "/ngo/campaigns/$cid" }) } 60)
Check 'Ravi (admin) told too' ($rn.Count -ge 1)

# --- privacy ---
$x = Api GET "/api/v1/donations/$($co.donationId)" $kiran
Check "Kiran cannot read Meera's donation (404)" ($x.__status -eq 404) (J $x)
$list = Api GET "/api/v1/ngos/$NgoId/donations?size=50" $asha
Check "NGO sees Meera's donation in its list" (@($list.items | Where-Object { $_.id -eq $co.donationId }).Count -eq 1) (J $list.items)
$x = Api GET "/api/v1/ngos/$NgoId/donations" $meera
Check 'donor cannot read NGO donation list (403)' ($x.__status -eq 403) (J $x)

# --- duplicate webhook, failure, cancel ---
$before = Raised $cid
$k1 = Checkout $kiran (@{ ngoId = $NgoId; campaignId = $cid; amount = '750'; donorName = 'Kiran Joshi'; donorEmail = 'kiran.donor@ngo.test'; wants80g = $false })
$p1 = Pay $kiran $k1.orderId 'DUPLICATE_WEBHOOK'
Check 'duplicate webhook: second delivery ignored' ($p1.status -eq 'PAID' -and @($p1.webhookResults)[1] -eq 'DUPLICATE_IGNORED') (J $p1)
$k2 = Checkout $kiran (@{ ngoId = $NgoId; campaignId = $cid; amount = '300'; donorName = 'Kiran Joshi'; donorEmail = 'kiran.donor@ngo.test'; wants80g = $false })
$p2 = Pay $kiran $k2.orderId 'FAILURE'
Check 'failed payment -> FAILED' ($p2.status -eq 'FAILED') (J $p2)
$k3 = Checkout $kiran (@{ ngoId = $NgoId; campaignId = $cid; amount = '200'; donorName = 'Kiran Joshi'; donorEmail = 'kiran.donor@ngo.test'; wants80g = $false })
$p3 = Pay $kiran $k3.orderId 'CANCELLATION'
Check 'cancelled payment -> CANCELLED' ($p3.status -eq 'CANCELLED') (J $p3)
Start-Sleep 6
Check 'campaign credited exactly once (+750 only)' ((Raised $cid) -eq $before + 750) "before=$before now=$(Raised $cid)"
$kh = @((Api GET '/api/v1/donations/mine' $kiran).items)
Check "Kiran's history: paid + failed shown, cancelled hidden" ((@($kh | Where-Object { $_.id -eq $k1.donationId }).Count -eq 1) -and (@($kh | Where-Object { $_.id -eq $k2.donationId -and $_.status -eq 'FAILED' }).Count -eq 1) -and (@($kh | Where-Object { $_.id -eq $k3.donationId }).Count -eq 0)) (J $kh)

# --- 80G donation + prefill ---
$g = $gift.Clone(); $g.amount = '2500'; $g.wants80g = $true; $g.donorPan = 'abcde1234f'; $g.donorAddress = '12 MG Road, Pune 411001'
$c80 = Checkout $meera $g
$p80 = Pay $meera $c80.orderId 'SUCCESS'
Check '80G donation paid' ($p80.status -eq 'PAID') (J $p80)
$tp = Api GET '/api/v1/donations/tax-profile' $meera
Check 'tax profile prefill: PAN upper-cased + address' ($tp.pan -eq 'ABCDE1234F' -and $tp.address -like '12 MG Road*') (J $tp)
$tk = Api GET '/api/v1/donations/tax-profile' $kiran
Check "Kiran's tax profile is empty (not Meera's)" ($null -eq $tk.pan) (J $tk)

# --- webhook security, closed campaign ---
$x = Api POST '/api/v1/webhooks/payments/mock' $null @{ id = 'evt_fake'; type = 'payment.captured' }
Check 'unsigned webhook rejected' ($x.__status -ge 400) (J $x)
$null = Api POST "$base/$cid/close" $asha
$x = Checkout $meera $gift
Check 'closed campaign refuses donations' ($x.__code -eq 'CAMPAIGN_NOT_LIVE') (J $x)

# cleanup: keep the sample campaigns and donors' histories tidy
& powershell -NoProfile -ExecutionPolicy Bypass -File "$PSScriptRoot\cleanup-e2e-donations.ps1" | Out-Null
& 'D:\tools\pgsql\bin\psql.exe' -w -h localhost -U postgres -d campaign -q -f "$PSScriptRoot\sql\cleanup-e2e-campaigns.sql" | Out-Null

""
"donations e2e: $pass passed, $fail failed"
if ($fail -gt 0) { exit 1 }
