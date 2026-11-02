# DEV/TEST ONLY: end-to-end utilisation certificate (campaign-project-service, Enterprise) with the standard dataset:
#   admin Asha, staff Sunil, campaign "School books for 200 children" with its approved expenses (seed-expenses.ps1).
# Plan gate, checks on the grant and period, live figures from the campaign accounts, draft PDF, NGO signature
# (figures frozen, CA link), the CA's public page and certification (ICAI membership no. + UDIN checks), final PDF in
# the vault (category CSR) and suggested for CSR packs, cancel (CA link closes), new CA link, read-only after a downgrade.
# Re-runnable: E2E certificates and their vault entries are removed at the start and end.
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


# Uploads with a real file name and real PDFs (as in seed-csr.ps1).
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
function Sha([byte[]]$b) { -join ([Security.Cryptography.SHA256]::Create().ComputeHash($b) | ForEach-Object { $_.ToString('x2') }) }
function Html($path) {
  # HttpClient keeps the body of error pages (Invoke-WebRequest in PowerShell 5.1 can lose it).
  Add-Type -AssemblyName System.Net.Http
  $c = New-Object Net.Http.HttpClient
  try {
    $r = $c.GetAsync("$api$path").Result
    [pscustomobject]@{ status = [int]$r.StatusCode; text = [Text.Encoding]::UTF8.GetString($r.Content.ReadAsByteArrayAsync().Result) }
  } finally { $c.Dispose() }
}
function Plan($p) { $null = Api PUT "/api/v1/ngos/$NgoId/subscription/trial-plan" $asha @{ plan = $p }; Start-Sleep 32 } # entitlement cache: 30 s

function Form($path, [hashtable]$fields) {
  Add-Type -AssemblyName System.Net.Http
  $c = New-Object Net.Http.HttpClient
  try {
    $kv = New-Object 'System.Collections.Generic.List[System.Collections.Generic.KeyValuePair[string,string]]'
    foreach ($k in $fields.Keys) { $kv.Add((New-Object 'System.Collections.Generic.KeyValuePair[string,string]' ($k, [string]$fields[$k]))) }
    $r = $c.PostAsync("$api$path", (New-Object Net.Http.FormUrlEncodedContent -ArgumentList (,$kv))).Result
    [pscustomobject]@{ status = [int]$r.StatusCode; text = [Text.Encoding]::UTF8.GetString($r.Content.ReadAsByteArrayAsync().Result) }
  } finally { $c.Dispose() }
}
function Cleanup {
  $sql = "BEGIN; ALTER TABLE utilisation_certificates DISABLE TRIGGER trg_uc_guard; " +
    "DELETE FROM utilisation_certificates WHERE funder_name LIKE 'E2E %'; " +
    "ALTER TABLE utilisation_certificates ENABLE TRIGGER trg_uc_guard; COMMIT;"
  & $psql -w -h localhost -U postgres -d campaign -q -c $sql 2>$null | Out-Null
  $sql = "BEGIN; ALTER TABLE document_activity DISABLE TRIGGER trg_document_activity_immutable; " +
    "DELETE FROM document_activity WHERE document_id IN (SELECT id FROM documents WHERE source = 'UTILISATION_CERT' AND title LIKE '%E2E %'); " +
    "DELETE FROM documents WHERE source = 'UTILISATION_CERT' AND title LIKE '%E2E %'; " +
    "ALTER TABLE document_activity ENABLE TRIGGER trg_document_activity_immutable; COMMIT;"
  & $psql -w -h localhost -U postgres -d document -q -c $sql 2>$null | Out-Null
}
function Day($n) { (Get-Date).AddDays($n).ToString('yyyy-MM-dd') }

$asha = Token 'asha.admin@ngo.test' 'DevNgo#2026'
$sunil = Token 'sunil.staff@ngo.test' 'DevTest#2026'
$base = "/api/v1/ngos/$NgoId/ucs"
Cleanup
$camp = @((Api GET "/api/v1/ngos/$NgoId/campaigns?size=50" $asha).items | Where-Object { $_.title -eq 'School books for 200 children' })[0]
if (-not $camp) { $camp = @((Api GET "/api/v1/ngos/$NgoId/campaigns" $asha) | ForEach-Object { $_ } | Where-Object { $_.title -eq 'School books for 200 children' })[0] }
$sheet = Api GET "/api/v1/ngos/$NgoId/campaigns/$($camp.id)/accounts" $asha
$from = [string]$camp.startDate; $to = Day 0
$uc = @{ campaignId = $camp.id; funderName = 'E2E Sahyadri Industries CSR Foundation'; funderReference = 'SCF/2026/14'; csr1Number = 'CSR00012345'
  grantAmount = '50000'; grantReceivedOn = $from; periodFrom = $from; periodTo = $to }

# --- plan gate -------------------------------------------------------------------------------------------------------
Plan 'PROFESSIONAL'
$x = Api POST $base $asha $uc
Check 'Professional: utilisation certificates refused (402, Enterprise)' ($x.__status -eq 402 -and $x.__code -eq 'ENTITLEMENT_REQUIRED:CSR_UTILISATION_CERT') (J $x)
Plan 'ENTERPRISE'
$x = Api POST $base $sunil $uc
Check 'staff cannot prepare certificates (403)' ($x.__status -eq 403) (J $x)
$bad = $uc.Clone(); $bad.periodTo = Day 3
$x = Api POST $base $asha $bad
Check 'period ending in the future refused' ($x.__status -eq 400 -and $x.__code -eq 'PERIOD_IN_FUTURE') (J $x)
$bad = $uc.Clone(); $bad.grantAmount = '0'
$x = Api POST $base $asha $bad
Check 'zero grant refused' ($x.__status -eq 400) (J $x)

# --- draft -----------------------------------------------------------------------------------------------------------
$d = Api POST $base $asha $uc
$approved = [int]$sheet.totals.approvedCount
Check 'Asha prepares a UC: numbered UC/<FY>/nnn, draft' ($d.status -eq 'DRAFT' -and $d.number -match '^UC/\d{4}-\d{2}/\d{3}$') (J @{ s = $d.status; n = $d.number })
Check 'figures come from the campaign accounts (approved expenses, by budget head)' (
  $d.figures.expenses -eq $approved -and [decimal]$d.figures.spent -eq [decimal]$sheet.totals.spent -and @($d.figures.heads).Count -ge 1) (J @{ e = $d.figures.expenses; a = $approved; s = $d.figures.spent; t = $sheet.totals.spent })
Check 'unspent = grant - utilised' ([decimal]$d.figures.unspent -eq ([decimal]$d.figures.grant - [decimal]$d.figures.utilised) -and -not $d.figures.frozen) (J $d.figures)
$p = Get-Bytes "$base/$($d.id)/pdf" $asha
Check 'draft PDF opens' ($p.status -eq 200 -and [Text.Encoding]::ASCII.GetString($p.bytes, 0, 5) -eq '%PDF-') "$($p.status)"
$u = $uc.Clone(); $u.grantAmount = '1000'; $u.version = $d.version
$d2 = Api PUT "$base/$($d.id)" $asha $u
Check 'grant smaller than the spending: the rest shows as own funds' ([decimal]$d2.figures.utilised -eq 1000 -and [decimal]$d2.figures.ownFunds -eq ([decimal]$d2.figures.spent - 1000)) (J $d2.figures)
$u.grantAmount = '50000'; $u.version = $d2.version
$d = Api PUT "$base/$($d.id)" $asha $u

# --- NGO signs -------------------------------------------------------------------------------------------------------
$x = Api POST "$base/$($d.id)/sign" $asha @{ signatoryName = 'Asha Patil'; signatoryRole = 'Chairman'; version = ($d.version - 1) }
Check 'stale signature refused (409)' ($x.__status -eq 409) (J $x)
$s = Api POST "$base/$($d.id)/sign" $asha @{ signatoryName = 'Asha Patil'; signatoryRole = 'Chairman'; version = $d.version }
Check 'Asha signs for the NGO: figures frozen, CA link open for 14 days' ($s.status -eq 'AWAITING_CA' -and $s.figures.frozen -and $s.caLink -match '/api/v1/public/uc/[A-Za-z0-9_-]{22}$' -and [math]::Round(([datetime]$s.caLinkExpiresAt - [datetime]$s.ngoSignedAt).TotalDays) -eq 14) (J @{ s = $s.status; l = $s.caLink })
$u.version = $s.version
$x = Api PUT "$base/$($d.id)" $asha $u
Check 'a signed UC cannot be edited' ($x.__status -eq 422 -and $x.__code -eq 'UC_SIGNED') (J $x)
$code = $s.caLink.Split('/')[-1]; $pub = "/api/v1/public/uc/$code"

# --- the CA ----------------------------------------------------------------------------------------------------------
$h = Html $pub
Check 'CA page opens without login: grant, statement, expenses and the form' ($h.status -eq 200 -and $h.text -match [regex]::Escape($s.number) -and $h.text -match 'Statement of utilisation' -and $h.text -match 'name=udin' -and $h.text -match 'Asha Patil') "$($h.status)"
$hdr = $null; try { $hdr = (Invoke-WebRequest "$api$pub" -UseBasicParsing).Headers['Content-Security-Policy'] } catch { }
Check "CA page: own styles and a form to this address only (CSP)" ($hdr -match "form-action 'self'" -and $hdr -match "default-src 'none'") "$hdr"
$dp = Get-Bytes "$pub/pdf" $null
Check 'CA downloads the draft PDF' ($dp.status -eq 200 -and [Text.Encoding]::ASCII.GetString($dp.bytes, 0, 5) -eq '%PDF-') "$($dp.status)"
$yy = (Get-Date).ToString('yy')
$f = Form "$pub/certify" @{ name = 'Meena Joshi'; membershipNo = '123456'; firm = 'Joshi & Co, Chartered Accountants'; firmRegNo = '012345W'; udin = "${yy}654321ABCDEFGHIJ"; place = 'Pune'; declare = 'yes' }
Check 'UDIN of another member refused, form kept' ($f.status -eq 400 -and $f.text -match 'does not belong' -and $f.text -match 'Meena Joshi') "$($f.status)"
$f = Form "$pub/certify" @{ name = 'Meena Joshi'; membershipNo = '123456'; udin = "${yy}123456ABCDEFGHIJ"; place = 'Pune' }
Check 'certifying without the declaration refused' ($f.status -eq 400 -and $f.text -match 'declaration') "$($f.status)"
$f = Form "$pub/certify" @{ name = 'Meena Joshi'; membershipNo = '123456'; firm = 'Joshi & Co, Chartered Accountants'; firmRegNo = '012345W'; udin = "${yy}123456ABCDEFGHIJ"; place = 'Pune'; declare = 'yes' }
Check 'CA certifies with membership no. and UDIN' ($f.status -eq 200 -and $f.text -match 'Certified on' -and $f.text -match 'UDIN') "$($f.status)"
$f = Form "$pub/certify" @{ name = 'Meena Joshi'; membershipNo = '123456'; udin = "${yy}123456ABCDEFGHIJ"; declare = 'yes' }
Check 'certifying twice refused' ($f.status -eq 409 -or ($f.status -eq 400 -and $f.text -match 'already')) "$($f.status)"
$c = Api GET "$base/$($d.id)" $asha
Check 'NGO sees it certified: CA name, M. No., UDIN, fingerprint' ($c.status -eq 'CA_CERTIFIED' -and $c.caName -eq 'Meena Joshi' -and $c.caMembershipNo -eq '123456' -and $c.caUdin -eq "${yy}123456ABCDEFGHIJ" -and $c.pdfSha256 -match '^[0-9a-f]{64}$') (J $c.status)
$fp = Get-Bytes "$base/$($d.id)/pdf" $asha
Check 'final PDF matches its recorded fingerprint' ($fp.status -eq 200 -and (Sha $fp.bytes) -eq $c.pdfSha256) "$($fp.status)"
$v = WaitFor { @((Api GET "/api/v1/ngos/$NgoId/library?q=Utilisation%20certificate&size=20" $asha).items | Where-Object { $_.title -match [regex]::Escape($c.number) -and $_.category -eq 'CSR' }) } 60
Check 'filed in the vault: category CSR, linked to the campaign' ($v -and @($v)[0].linkId -eq $camp.id) (J $v)
$sg = Api GET "/api/v1/ngos/$NgoId/csr/suggestions" $asha
Check 'suggested for CSR packs as a utilisation certificate' (@($sg.UTILISATION | Where-Object { $_.title -match [regex]::Escape($c.number) }).Count -eq 1) (J $sg.UTILISATION)

# --- cancel, new link ------------------------------------------------------------------------------------------------
$k = Api POST "$base/$($d.id)/cancel" $asha @{ reason = 'E2E wrong period' }
$h = Html $pub
Check 'cancelled: the CA page answers 410' ($k.status -eq 'CANCELLED' -and $h.status -eq 410) "$($k.status) $($h.status)"
$v = WaitFor { @((Api GET "/api/v1/ngos/$NgoId/library?q=Cancelled&size=20" $asha).items | Where-Object { $_.title -match [regex]::Escape($c.number) }) } 60
Check 'vault entry renamed "Cancelled: ..."' ($null -ne $v) (J $v)
$d = Api POST $base $asha $uc
$s = Api POST "$base/$($d.id)/sign" $asha @{ signatoryName = 'Asha Patil'; signatoryRole = 'Chairman'; version = $d.version }
$old = $s.caLink.Split('/')[-1]
$n = Api POST "$base/$($d.id)/ca-link" $asha
$h1 = Html "/api/v1/public/uc/$old"; $h2 = Html "/api/v1/public/uc/$($n.caLink.Split('/')[-1])"
Check 'new CA link: the old one stops, the new one opens' ($h1.status -eq 404 -and $h2.status -eq 200) "$($h1.status) $($h2.status)"
$h = Html "/api/v1/public/uc/AAAAAAAAAAAAAAAAAAAAAA"
Check 'unknown link: not found' ($h.status -eq 404)

# --- after a downgrade -----------------------------------------------------------------------------------------------
Plan 'PROFESSIONAL'
$x = Api POST $base $asha $uc
Check 'after a downgrade no new certificates' ($x.__status -eq 402) (J $x)
$g = Api GET "$base/$($d.id)" $asha
$k = Api POST "$base/$($d.id)/cancel" $asha @{ reason = 'E2E end of test' }
Check 'but old ones stay readable and can be cancelled' ($g.status -eq 'AWAITING_CA' -and $k.status -eq 'CANCELLED') "$($g.status) $($k.status)"

Cleanup
"RESULT: $pass passed, $fail failed"
