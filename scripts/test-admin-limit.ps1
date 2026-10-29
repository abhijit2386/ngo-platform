# DEV/TEST ONLY: an NGO has at most 2 administrators (primary + secondary). Uses the standard dataset, where
# Asha Seva already has Asha (primary) and Ravi (secondary): every way of adding a third admin must be refused.
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

$asha = Token 'asha.admin@ngo.test' 'DevNgo#2026'
$ravi = Token 'ravi.staff@ngo.test' 'DevStaff#2026'
Check 'tokens issued' ($asha -and $ravi)
$cap = Api GET "/api/v1/ngos/$NgoId/staff/capacity" $asha
Check 'capacity reports 2 of 2 administrators' ($cap.adminMax -eq 2 -and ($cap.activeAdmins + $cap.pendingAdmins) -eq 2) (J $cap)
$x = Api POST "/api/v1/ngos/$NgoId/staff/invitations" $asha @{ email = 'third.admin.test@ngo.test'; role = 'SECONDARY_ADMIN' }
Check 'primary admin cannot invite a third admin' ($x.__code -eq 'ADMIN_LIMIT_REACHED') (J $x)
$x = Api POST "/api/v1/ngos/$NgoId/staff/invitations" $ravi @{ email = 'third.admin.test@ngo.test'; role = 'SECONDARY_ADMIN' }
Check 'secondary admin cannot invite a third admin' ($x.__code -eq 'ADMIN_LIMIT_REACHED') (J $x)
$staff = @((Api GET "/api/v1/ngos/$NgoId/staff" $asha) | ForEach-Object { $_ })  # PS 5.1: unroll the JSON array
$sunil = @($staff | Where-Object { $_.email -eq 'sunil.staff@ngo.test' })[0]
Check 'found Sunil in the team list' ($null -ne $sunil.membershipId) (J $staff)
$x = Api PUT "/api/v1/ngos/$NgoId/staff/$($sunil.membershipId)/role" $asha @{ role = 'SECONDARY_ADMIN' }
Check 'promoting staff to a third admin is refused' ($x.__code -eq 'ADMIN_LIMIT_REACHED' -or $x.__code -eq 'STEP_UP_REQUIRED') (J $x)
$after = @((Api GET "/api/v1/ngos/$NgoId/staff" $asha) | ForEach-Object { $_ })
Check 'Sunil is still staff' (@($after | Where-Object { $_.email -eq 'sunil.staff@ngo.test' })[0].role -eq 'STAFF')
$admins = @($after | Where-Object { $_.role -in 'PRIMARY_ADMIN', 'SECONDARY_ADMIN' -and $_.status -eq 'ACTIVE' })
Check 'still exactly 2 active administrators' ($admins.Count -eq 2) (J $admins)

""
"admin limit e2e: $pass passed, $fail failed"
if ($fail -gt 0) { exit 1 }
