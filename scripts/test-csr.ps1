# DEV/TEST ONLY: end-to-end CSR compliance pack (document-service, Enterprise) with the standard dataset:
#   admin Asha, staff Sunil, the vault papers from seed-documents.ps1 + seed-csr.ps1.
# Plan gate (Professional refused, Enterprise allowed), suggestions from the vault, auto-filled draft, readiness
# (missing / expiring / expired papers), sharing with frozen files, the company's public page, downloads matching
# their SHA-256 fingerprint, ZIP of everything, view log, vault edits not changing what was sent, revoke (410),
# copy, read-only after a downgrade. Re-runnable: E2E packs and E2E vault files are removed at the start and end.
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
function Cleanup {
  $sql = "BEGIN; ALTER TABLE csr_pack_items DISABLE TRIGGER trg_csr_item_frozen; ALTER TABLE csr_pack_views DISABLE TRIGGER trg_csr_views_immutable; " +
    "ALTER TABLE document_activity DISABLE TRIGGER trg_document_activity_immutable; " +
    "CREATE TEMP TABLE e2e_p ON COMMIT DROP AS SELECT id FROM csr_packs WHERE title LIKE 'E2E %'; " +
    "DELETE FROM csr_pack_views WHERE pack_id IN (SELECT id FROM e2e_p); DELETE FROM csr_pack_items WHERE pack_id IN (SELECT id FROM e2e_p); " +
    "DELETE FROM csr_packs WHERE id IN (SELECT id FROM e2e_p); " +
    "DELETE FROM document_activity WHERE document_id IN (SELECT id FROM documents WHERE title LIKE 'E2E CSR %'); " +
    "DELETE FROM documents WHERE title LIKE 'E2E CSR %'; " +
    "ALTER TABLE document_activity ENABLE TRIGGER trg_document_activity_immutable; ALTER TABLE csr_pack_views ENABLE TRIGGER trg_csr_views_immutable; " +
    "ALTER TABLE csr_pack_items ENABLE TRIGGER trg_csr_item_frozen; COMMIT;"
  & $psql -w -h localhost -U postgres -d document -q -c $sql 2>$null | Out-Null
}

$asha = Token 'asha.admin@ngo.test' 'DevNgo#2026'
$sunil = Token 'sunil.staff@ngo.test' 'DevTest#2026'
$base = "/api/v1/ngos/$NgoId/csr"; $lib = "/api/v1/ngos/$NgoId/library"
Cleanup
& powershell -NoProfile -ExecutionPolicy Bypass -File "$PSScriptRoot\seed-csr.ps1" | Out-Null

# --- plan gate -------------------------------------------------------------------------------------------------------
Plan 'PROFESSIONAL'
$ov = Api GET "$base/overview" $asha
Check 'Professional: CSR packs shown as an Enterprise feature' ($ov.enabled -eq $false -and @($ov.kinds).Count -eq 12) (J $ov.enabled)
$x = Api POST "$base/packs" $asha @{ title = 'E2E blocked'; recipientName = 'E2E Co' }
Check 'Professional: creating a pack is refused (402, Enterprise)' ($x.__status -eq 402 -and $x.__code -eq 'ENTITLEMENT_REQUIRED:CSR_COMPLIANCE_PACK') (J $x)
Plan 'ENTERPRISE'
$ov = Api GET "$base/overview" $asha
Check 'Enterprise: CSR packs enabled' ($ov.enabled -eq $true) (J $ov.enabled)

# --- suggestions and draft -------------------------------------------------------------------------------------------
$sg = Api GET "$base/suggestions" $asha
Check 'vault papers recognised: 80G, 12A, PAN, CSR-1, audit, registration, board, annual report' (
  @($sg.REG_80G).Count -ge 1 -and @($sg.REG_12A).Count -ge 1 -and @($sg.PAN).Count -ge 1 -and @($sg.CSR1).Count -ge 1 -and
  @($sg.AUDITED_ACCOUNTS).Count -ge 1 -and @($sg.REGISTRATION).Count -ge 1 -and @($sg.BOARD_LIST).Count -ge 1 -and @($sg.ANNUAL_REPORT).Count -ge 1) (J $sg)
$x = Api GET "$base/suggestions" $sunil
Check 'staff cannot open CSR packs (403)' ($x.__status -eq 403) (J $x)
$x = Api POST "$base/packs" $asha @{ title = 'E2E x'; recipientName = ' ' }
Check 'company name is required' ($x.__status -eq 400) (J $x)
# Own FCRA paper expiring in 20 days (the seeded one runs out after a while): it is the newest, so it is picked.
$script:FileName = 'E2E CSR FCRA certificate.pdf'
$fc = Multi $lib $asha 'meta' @{ title = 'E2E CSR FCRA certificate'; category = 'TAX_COMPLIANCE'; tags = @('fcra'); documentDate = (Get-Date).ToString('yyyy-MM-dd'); expiresOn = (Get-Date).AddDays(20).ToString('yyyy-MM-dd'); visibility = 'TEAM' } 'file' @(, (RealPdf @('FCRA registration certificate', 'E2E'))) 'application/pdf'
$p = Api POST "$base/packs" $asha @{ title = 'E2E CSR pack FY 2026-27'; recipientName = 'E2E Tata Trusts CSR'; recipientEmail = 'csr@e2e.test'; message = "Dear CSR team,`nPlease find our compliance documents."; validDays = 15 }
$items = @($p.items | ForEach-Object { $_ })
Check 'Asha creates a pack: filled from the vault automatically' ($p.status -eq 'DRAFT' -and $items.Count -ge 8) "$($items.Count) $(J $p.status)"
Check 'every required paper found (registration, PAN, 12A, 80G, CSR-1, audited accounts)' (@($p.readiness.warnings | Where-Object { $_ -like 'MISSING:*' }).Count -eq 0) (J $p.readiness)
Check 'FCRA certificate expiring in 20 days is flagged' (@($p.readiness.warnings | Where-Object { $_ -like 'EXPIRES_SOON:*FCRA*' }).Count -eq 1) (J $p.readiness.warnings)
$vol = @((Api GET "${lib}?q=Volunteer&size=5" $asha).items)[0]
$p2 = Api POST "$base/packs/$($p.id)/items" $asha @{ documentId = $vol.id; kind = 'OTHER' }
Check 'another vault document can be added' (@($p2.items).Count -eq $items.Count + 1) "$(@($p2.items).Count)"
$x = Api POST "$base/packs/$($p.id)/items" $asha @{ documentId = $vol.id; kind = 'OTHER' }
Check 'the same document twice is refused' ($x.__status -eq 409) (J $x)
$x = Api POST "$base/packs/$($p.id)/items" $asha @{ documentId = $vol.id; kind = 'PASSPORT' }
Check 'unknown kind refused' ($x.__status -eq 400) (J $x)
$it = @($p2.items | Where-Object { $_.documentId -eq $vol.id })[0]
$p = Api DELETE "$base/packs/$($p.id)/items/$($it.id)" $asha
Check 'and removed again' (@($p.items).Count -eq $items.Count) "$(@($p.items).Count)"

# --- sharing ---------------------------------------------------------------------------------------------------------
$x = Api POST "$base/packs/$($p.id)/share" $asha @{ version = $p.version }
Check 'sharing with a warning needs confirmation' ($x.__status -eq 422 -and $x.__code -eq 'PACK_HAS_WARNINGS') (J $x)
$x = Api POST "$base/packs/$($p.id)/share" $asha @{ version = ($p.version - 1); acceptWarnings = $true }
Check 'stale version refused' ($x.__status -eq 409) (J $x)
$s = Api POST "$base/packs/$($p.id)/share" $asha @{ version = $p.version; acceptWarnings = $true }
$days = ([datetime]$s.expiresAt - [datetime]$s.sharedAt).TotalDays
Check 'Asha shares the pack: link open for 15 days' ($s.status -eq 'SHARED' -and $s.link -match '/api/v1/public/csr/[A-Za-z0-9_-]{22}$' -and [math]::Round($days) -eq 15) (J @{ s = $s.status; l = $s.link; d = $days })
Check 'every file frozen with its SHA-256 fingerprint' (@($s.items | Where-Object { $_.sha256 -match '^[0-9a-f]{64}$' }).Count -eq @($s.items).Count) (J $s.items[0])
$x = Api POST "$base/packs/$($p.id)/items" $asha @{ documentId = $vol.id; kind = 'OTHER' }
Check 'a shared pack cannot be changed' ($x.__status -eq 422 -and $x.__code -eq 'PACK_ALREADY_SHARED') (J $x)
$code = $s.link.Split('/')[-1]; $pub = "/api/v1/public/csr/$code"

# --- the company's side ----------------------------------------------------------------------------------------------
$h = Html $pub
Check 'company page opens without login, with NGO name and documents' ($h.status -eq 200 -and $h.text -match 'Asha Seva' -and $h.text -match 'Download all' -and $h.text -match 'CSR-1 registration') "$($h.status)"
Check 'company page is not indexed by search engines' ($h.text -match 'noindex')
$hdr = $null; try { $hdr = (Invoke-WebRequest "$api$pub" -UseBasicParsing).Headers['Content-Security-Policy'] } catch { }
Check 'company page may use its own styles and nothing else (CSP)' ($hdr -match "style-src 'unsafe-inline'" -and $hdr -match "default-src 'none'") "$hdr"
$pj = Api GET "${pub}?format=json" $null
$first = @($pj.items | Where-Object { $_.kind -eq 'CSR1' })[0]
$firstDoc = (@($s.items | Where-Object { $_.kind -eq 'CSR1' })[0]).documentId
Check 'JSON view lists every document as available' (@($pj.items).Count -eq @($s.items).Count -and @($pj.items | Where-Object { -not $_.available }).Count -eq 0) (J $pj)
$f = Get-Bytes "$pub/files/$($first.id)" $null
Check 'company downloads CSR-1: bytes match the recorded fingerprint' ($f.status -eq 200 -and (Sha $f.bytes) -eq $first.sha256) "$($f.status)"
$z = Get-Bytes "$pub/all.zip" $null
$zipOk = $false; $entries = 0
if ($z.status -eq 200) {
  Add-Type -AssemblyName System.IO.Compression
  $zs = New-Object IO.MemoryStream(, [byte[]]$z.bytes); $za = New-Object IO.Compression.ZipArchive($zs)
  $entries = $za.Entries.Count; $za.Dispose(); $zipOk = $entries -eq @($pj.items).Count
}
Check 'Download all: one ZIP with every document' $zipOk "$($z.status) entries=$entries"
$v = @(Api GET "$base/packs/$($p.id)/views" $asha | ForEach-Object { $_ })
Check 'NGO sees the visit and both downloads' (@($v | Where-Object { $_.action -eq 'OPENED' }).Count -ge 1 -and @($v | Where-Object { $_.action -eq 'DOWNLOADED' }).Count -ge 1 -and @($v | Where-Object { $_.action -eq 'DOWNLOADED_ALL' }).Count -eq 1) (J $v)
$d = Api GET "$base/packs/$($p.id)" $asha
Check 'pack shows opened / download counts' ($d.opened -ge 1 -and $d.downloads -ge 2) (J @{ o = $d.opened; d = $d.downloads })

# vault changes after sharing do not change what was sent
$csr1 = Api GET "$lib/$firstDoc" $asha
$u = Api PUT "$lib/$($csr1.id)" $asha @{ title = 'CSR-1 registration (MCA) - renamed'; category = $csr1.category; tags = $csr1.tags; visibility = $csr1.visibility; documentDate = $csr1.documentDate; version = $csr1.version }
$pj2 = Api GET "${pub}?format=json" $null
Check 'renaming in the vault does not change the shared title' ((@($pj2.items | Where-Object { $_.id -eq $first.id })[0]).title -eq $first.title) (J $u.title)
$del = Api DELETE "$lib/$($csr1.id)" $asha
$f = Get-Bytes "$pub/files/$($first.id)" $null
Check 'deleting it in the vault keeps the shared file downloadable' ($del.status -eq 'DELETED' -and $f.status -eq 200 -and (Sha $f.bytes) -eq $first.sha256) "$($del.status) $($f.status)"
$r = Api POST "$lib/$($csr1.id)/restore" $asha
$u = Api GET "$lib/$($csr1.id)" $asha
$null = Api PUT "$lib/$($csr1.id)" $asha @{ title = 'CSR-1 registration (MCA)'; category = $u.category; tags = $u.tags; visibility = $u.visibility; documentDate = $u.documentDate; version = $u.version }
Check 'restored and renamed back' ($r.status -eq 'ACTIVE')

$h = Html "/api/v1/public/csr/AAAAAAAAAAAAAAAAAAAAAA"
Check 'unknown link: not found' ($h.status -eq 404)
$h = Html "/api/v1/public/csr/..%2F..%2Fetc"
Check 'malformed link: not found' ($h.status -eq 404 -or $h.status -eq 400) "$($h.status)"

# --- stop, copy, expired paper ---------------------------------------------------------------------------------------
$x = Api POST "$base/packs/$($p.id)/revoke" $sunil
Check 'staff cannot stop a link' ($x.__status -eq 403) (J $x)
$rv = Api POST "$base/packs/$($p.id)/revoke" $asha
$h = Html $pub
$f = Get-Bytes "$pub/files/$($first.id)" $null
Check 'Asha stops the link: page and files answer 410 Gone' ($rv.status -eq 'REVOKED' -and $h.status -eq 410 -and $h.text -match 'withdrawn' -and $f.status -eq 410) "$($rv.status) $($h.status) $($f.status)"
$c = Api POST "$base/packs/$($p.id)/copy" $asha
Check 'copy makes a new draft with the same documents' ($c.status -eq 'DRAFT' -and @($c.items).Count -eq @($s.items).Count -and $c.recipientName -eq 'E2E Tata Trusts CSR') "$($c.status) $(@($c.items).Count)"
$script:FileName = 'E2E CSR expired 80G.pdf'
$old = Multi $lib $asha 'meta' @{ title = 'E2E CSR expired 80G'; category = 'TAX_COMPLIANCE'; tags = @('80g'); expiresOn = (Get-Date).AddDays(-1).ToString('yyyy-MM-dd'); visibility = 'TEAM' } 'file' @(, (RealPdf @('80G order', 'expired'))) 'application/pdf'
$c2 = Api POST "$base/packs/$($c.id)/items" $asha @{ documentId = $old.id; kind = 'REG_80G' }
$x = Api POST "$base/packs/$($c.id)/share" $asha @{ version = $c2.version; acceptWarnings = $true }
Check 'an expired paper blocks sharing' ($x.__status -eq 422 -and $x.__code -eq 'PACK_NOT_READY' -and $x.__body -match 'EXPIRED') (J $x)
$oi = @($c2.items | Where-Object { $_.documentId -eq $old.id })[0]
$c3 = Api DELETE "$base/packs/$($c.id)/items/$($oi.id)" $asha
$c4 = Api POST "$base/packs/$($c.id)/share" $asha @{ version = $c3.version; acceptWarnings = $true }
Check 'without it the copy can be shared' ($c4.status -eq 'SHARED') (J $c4.status)

# --- after a downgrade -----------------------------------------------------------------------------------------------
Plan 'PROFESSIONAL'
$x = Api POST "$base/packs" $asha @{ title = 'E2E after downgrade'; recipientName = 'E2E Co' }
Check 'after a downgrade no new packs' ($x.__status -eq 402) (J $x)
$g = Api GET "$base/packs/$($c.id)" $asha
Check 'but old packs stay readable' ($g.status -eq 'SHARED' -and $g.link) (J $g.status)
$rv = Api POST "$base/packs/$($c.id)/revoke" $asha
Check 'and a link can still be stopped' ($rv.status -eq 'REVOKED') (J $rv)

Cleanup
"RESULT: $pass passed, $fail failed"
