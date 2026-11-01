# DEV/TEST ONLY: standard dataset for the document vault and meetings (idempotent by title).
#   Documents: compliance file with an expiry date, audit report, volunteer guidelines, a field photo, rent agreement.
#   Meetings: AGM held and approved (FY 2026-27), October board meeting approved with an action item for Sunil,
#   November board meeting with draft minutes (waiting for approval), upcoming staff and committee meetings.
param([string]$NgoId = '0cf945f6-1183-42f5-aeb1-20bab9be302b')
$ErrorActionPreference = 'Stop'
$api = 'http://localhost:8080'; $kc = 'http://localhost:8180/realms/ngo/protocol/openid-connect/token'
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
    $i++; $form.Add($part, $fileName, $(if ($script:FileName) { $script:FileName } else { "bill$i.png" }))
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
    $r = Invoke-WebRequest "$api$path" -Headers @{ Authorization = "Bearer $tok" } -UseBasicParsing
    $ms = New-Object IO.MemoryStream; $r.RawContentStream.CopyTo($ms)
    [pscustomobject]@{ status = [int]$r.StatusCode; type = $r.Headers['Content-Type']; bytes = $ms.ToArray(); disp = $r.Headers['Content-Disposition'] }
  } catch { [pscustomobject]@{ status = [int]$_.Exception.Response.StatusCode; bytes = @() } }
}

# A small but valid one-page PDF with a few lines of text (viewers open it).
function Day($n) { (Get-Date).AddDays($n).ToString('yyyy-MM-dd') }
function RealPdf([string[]]$lines) {
  $text = "BT /F1 16 Tf 60 780 Td 22 TL"
  foreach ($l in $lines) { $text += " (" + $l.Replace('\', '\\').Replace('(', '\(').Replace(')', '\)') + ") '" }
  $text += " ET"
  $objs = @(
    "<< /Type /Catalog /Pages 2 0 R >>",
    "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
    "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
    "<< /Length $($text.Length) >>`nstream`n$text`nendstream")
  $sb = New-Object Text.StringBuilder; [void]$sb.Append("%PDF-1.4`n"); $off = @()
  for ($i = 0; $i -lt $objs.Count; $i++) { $off += $sb.Length; [void]$sb.Append("$($i + 1) 0 obj`n$($objs[$i])`nendobj`n") }
  $x = $sb.Length
  [void]$sb.Append("xref`n0 $($objs.Count + 1)`n0000000000 65535 f `n")
  foreach ($o in $off) { [void]$sb.Append(('{0:D10} 00000 n ' -f $o) + "`n") }
  [void]$sb.Append("trailer << /Size $($objs.Count + 1) /Root 1 0 R >>`nstartxref`n$x`n%%EOF`n")
  return , [Text.Encoding]::ASCII.GetBytes($sb.ToString())
}
function Photo() {
  Add-Type -AssemblyName System.Drawing
  $bmp = New-Object Drawing.Bitmap 1200, 800
  $g = [Drawing.Graphics]::FromImage($bmp); $g.Clear([Drawing.Color]::FromArgb(222, 236, 214))
  $g.FillEllipse((New-Object Drawing.SolidBrush ([Drawing.Color]::FromArgb(250, 200, 90))), 900, 60, 180, 180)
  $g.FillRectangle((New-Object Drawing.SolidBrush ([Drawing.Color]::FromArgb(120, 160, 110))), 0, 560, 1200, 240)
  $g.DrawString('Health camp, Wadi - 26 Oct 2026', (New-Object Drawing.Font('Arial', 40)), [Drawing.Brushes]::Black, 60, 640)
  $g.Dispose()
  $ms = New-Object IO.MemoryStream; $bmp.Save($ms, [Drawing.Imaging.ImageFormat]::Jpeg); $bmp.Dispose()
  return , $ms.ToArray()
}
function At([string]$localDateTime) { ([datetime]::ParseExact($localDateTime, 'yyyy-MM-dd HH:mm', $null)).AddHours(-5.5).ToString('yyyy-MM-ddTHH:mm:ssZ') }
function Iso($days, $hour) { (Get-Date).Date.AddDays($days).AddHours($hour - 5.5).ToString('yyyy-MM-ddTHH:mm:ssZ') }

$asha = Token 'asha.admin@ngo.test' 'DevNgo#2026'
$ravi = Token 'ravi.staff@ngo.test' 'DevStaff#2026'
$sunil = Token 'sunil.staff@ngo.test' 'DevTest#2026'
$priya = Token 'priya.admin@ngo.test' 'DevTest#2026'
$team = @((Api GET "/api/v1/ngos/$NgoId/staff" $asha) | ForEach-Object { $_ })
function Uid($email) { @($team | Where-Object { $_.email -eq $email })[0].userId }
$lib = "/api/v1/ngos/$NgoId/library"; $mb = "/api/v1/ngos/$NgoId/meetings"

# --- documents ---
$have = @((Api GET "${lib}?size=200" $asha).items | ForEach-Object { $_.title })
function Doc($tok, $meta, $bytes, $type) {
  if ($have -contains $meta.title) { "exists: $($meta.title)"; return }
  $script:FileName = ($meta.title -replace '[^A-Za-z0-9 -]', '') + $(if ($type -eq 'image/jpeg') { '.jpg' } else { '.pdf' })
  $r = Multi $lib $tok 'meta' $meta 'file' @(, $bytes) $type
  if ($r.__status -eq 201) { "uploaded: $($meta.title)" } else { "FAILED: $($meta.title) $(J $r)" }
}
Doc $asha @{ title = 'FCRA renewal acknowledgement'; category = 'TAX_COMPLIANCE'; tags = @('fcra', 'renewal'); documentDate = (Day -340); expiresOn = (Day 20); visibility = 'TEAM'; description = 'Acknowledgement of the FCRA renewal application. Renew before it expires.' } (RealPdf @('FCRA renewal - acknowledgement', 'Asha Seva Foundation', 'Valid for 12 months')) 'application/pdf'
Doc $priya @{ title = 'Audit report FY 2025-26'; category = 'FINANCE'; tags = @('audit', 'fy2025-26'); documentDate = '2026-08-30'; visibility = 'TEAM' } (RealPdf @('Audit report FY 2025-26', 'Statement of accounts', 'CA Mehta & Associates')) 'application/pdf'
Doc $sunil @{ title = 'Volunteer guidelines 2026'; category = 'OTHER'; tags = @('volunteers', 'guidelines'); documentDate = (Day -30); visibility = 'TEAM' } (RealPdf @('Volunteer guidelines 2026', '1. Be on time', '2. Respect every beneficiary')) 'application/pdf'
Doc $sunil @{ title = 'Health camp, Wadi'; category = 'PHOTOS_REPORTS'; tags = @('health-camp', 'wadi'); documentDate = (Day -6); visibility = 'TEAM' } (Photo) 'image/jpeg'
Doc $ravi @{ title = 'Office rent agreement 2026-27'; category = 'AGREEMENT'; tags = @('rent', 'office'); documentDate = '2026-04-01'; expiresOn = '2027-03-31' } (RealPdf @('Leave and licence agreement', 'Office premises, Pune', '1 Apr 2026 - 31 Mar 2027')) 'application/pdf'

# --- meetings ---
$meet = @((Api GET "${mb}?when=all" $asha) | ForEach-Object { $_ })
function Existing($title) { @($meet | Where-Object { $_.title -eq $title -and $_.status -ne 'CANCELLED' })[0] }
$sunilId = Uid 'sunil.staff@ngo.test'
$board = @(
  @{ userId = (Uid 'asha.admin@ngo.test'); designation = 'President' },
  @{ userId = (Uid 'ravi.staff@ngo.test'); designation = 'Secretary' },
  @{ userId = (Uid 'priya.admin@ngo.test'); designation = 'Treasurer' })
$everyone = $board + @(@{ userId = $sunilId; designation = 'Programme officer' }, @{ name = 'CA Mehta'; designation = 'Auditor' })
function Held($m, $present, $minutes, $resolutions, $actions, [bool]$approve) {
  $d = Api GET "$mb/$($m.meeting.id)" $asha
  $att = @{}; foreach ($a in $d.attendees) { $att[$a.id] = -not ($present -notcontains $a.name) }
  $d = Api POST "$mb/$($d.meeting.id)/minutes" $asha @{ minutes = $minutes; chairperson = 'Asha Patil'; attendance = $att; version = $d.meeting.version }
  foreach ($r in $resolutions) { $d = Api POST "$mb/$($d.meeting.id)/resolutions" $asha $r }
  foreach ($a in $actions) { $d = Api POST "$mb/$($d.meeting.id)/actions" $asha $a }
  if ($approve) { $d = Api POST "$mb/$($d.meeting.id)/approve" $asha @{ version = $d.meeting.version } }
  "  -> $($d.meeting.status) $(@($d.resolutions | ForEach-Object { $_.number }) -join ' ')"
}
function Meeting($req, $after) {
  if (Existing $req.title) { "exists: $($req.title)"; return }
  $m = Api POST $mb $asha $req
  if (-not $m.meeting) { "FAILED: $($req.title) $(J $m)"; return }
  "scheduled: $($req.title)"
  if ($after) { & $after $m }
}
$names = @('Asha Patil', 'Ravi Kulkarni', 'Priya Deshmukh', 'Sunil Jadhav', 'CA Mehta')
Meeting @{ type = 'AGM'; title = 'Annual General Meeting 2026'; startsAt = (At '2026-09-20 11:00'); mode = 'IN_PERSON'; venue = 'Community hall, Wadi'; quorumRequired = 3; attendees = $everyone
  agenda = "1. Confirm minutes of the last AGM`n2. Adopt audited accounts FY 2025-26`n3. Appoint the auditor for FY 2026-27`n4. Annual report" } {
  param($m)
  Held $m $names "The President welcomed the members. The audited accounts for FY 2025-26 were presented by the Treasurer and discussed.`n`nThe annual report on education and health programmes was read and accepted." @(
    @{ text = 'the audited accounts for FY 2025-26 are adopted.'; proposedBy = 'Priya Deshmukh'; secondedBy = 'Ravi Kulkarni'; votesFor = 4; votesAgainst = 0; result = 'PASSED' },
    @{ text = 'CA Mehta & Associates are appointed auditors for FY 2026-27.'; proposedBy = 'Ravi Kulkarni'; secondedBy = 'Asha Patil'; votesFor = 4; votesAgainst = 0; result = 'PASSED' }) @() $true }
Meeting @{ type = 'BOARD'; title = 'Board meeting - October'; startsAt = (At '2026-10-10 16:00'); mode = 'HYBRID'; venue = 'NGO office'; onlineLink = 'https://meet.example/board'; quorumRequired = 2; attendees = $board
  agenda = "1. School books campaign review`n2. Second lot of school bags" } {
  param($m)
  Held $m @('Asha Patil', 'Ravi Kulkarni', 'Priya Deshmukh') "The board reviewed spending on the School books campaign against the budget." @(
    @{ text = 'the second lot of 160 school bags be purchased from Sharma Stationers.'; proposedBy = 'Ravi Kulkarni'; secondedBy = 'Priya Deshmukh'; votesFor = 3; votesAgainst = 0; result = 'PASSED' }) @(
    @{ text = 'Collect three quotations for school bags'; ownerUserId = $sunilId; dueDate = (Day 5) },
    @{ text = 'Share the campaign account sheet with the board'; ownerUserId = (Uid 'priya.admin@ngo.test'); dueDate = (Day 12) }) $true }
Meeting @{ type = 'BOARD'; title = 'Board meeting - November'; startsAt = (Iso -1 17); mode = 'IN_PERSON'; venue = 'NGO office'; quorumRequired = 2; attendees = $board
  agenda = "1. Health camp report`n2. FCRA renewal" } {
  param($m)
  Held $m @('Asha Patil', 'Ravi Kulkarni') "The health camp at Wadi served 140 people. The FCRA renewal is due next month." @(
    @{ text = 'the FCRA renewal be filed before the due date by the Secretary.'; proposedBy = 'Asha Patil'; secondedBy = 'Ravi Kulkarni'; votesFor = 2; votesAgainst = 0; result = 'PASSED' }) @(
    @{ text = 'File the FCRA renewal'; ownerUserId = (Uid 'ravi.staff@ngo.test'); dueDate = (Day 15) }) $false }
Meeting @{ type = 'STAFF'; title = 'Staff meeting - campaign planning'; startsAt = (Iso 3 10); mode = 'ONLINE'; onlineLink = 'https://meet.example/staff'; agenda = "1. December campaigns`n2. Volunteer roster"; attendees = $everyone[0..3]; visibility = 'TEAM' } $null
Meeting @{ type = 'COMMITTEE'; title = 'Health committee'; startsAt = (Iso 10 15); mode = 'IN_PERSON'; venue = 'Primary health centre, Wadi'; agenda = '1. Next health camp'; attendees = @($board[0], @{ userId = $sunilId; designation = 'Coordinator' }) } $null
"seed-documents: done"
