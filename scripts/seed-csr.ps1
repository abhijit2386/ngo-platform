# DEV/TEST ONLY: CSR papers of the standard dataset in Asha Seva's document vault (idempotent by title), so the CSR
# compliance pack has something to find: PAN, 80G and 12A orders, trust registration certificate, CSR-1 registration,
# board of trustees, annual report. The audit report and the FCRA acknowledgement (expiring in 20 days) come from
# seed-documents.ps1. Run after seed-documents.ps1.
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
$lib = "/api/v1/ngos/$NgoId/library"
$have = @((Api GET "${lib}?size=200" $asha).items | ForEach-Object { $_.title })
function Doc($tok, $meta, $bytes, $type) {
  if ($have -contains $meta.title) { "exists: $($meta.title)"; return }
  $script:FileName = ($meta.title -replace '[^A-Za-z0-9 -]', '') + '.pdf'
  $r = Multi $lib $tok 'meta' $meta 'file' @(, $bytes) $type
  if ($r.__status -eq 201) { "uploaded: $($meta.title)" } else { "FAILED: $($meta.title) $(J $r)" }
}
Doc $asha @{ title = 'PAN card - Asha Seva Foundation'; category = 'TAX_COMPLIANCE'; tags = @('pan'); documentDate = '2015-06-12'; visibility = 'TEAM' } (RealPdf @('Permanent Account Number', 'Asha Seva Foundation', 'PAN AAATA1234T')) 'application/pdf'
Doc $asha @{ title = '80G approval order'; category = 'TAX_COMPLIANCE'; tags = @('80g'); documentDate = '2026-04-01'; expiresOn = '2031-03-31'; visibility = 'TEAM' } (RealPdf @('Order under section 80G(5)', 'Asha Seva Foundation', 'AAATA0000A25PN01', 'Valid AY 2027-28 to AY 2031-32')) 'application/pdf'
Doc $asha @{ title = '12A registration order'; category = 'TAX_COMPLIANCE'; tags = @('12a'); documentDate = '2026-04-01'; expiresOn = '2031-03-31'; visibility = 'TEAM' } (RealPdf @('Registration under section 12A/12AB', 'Asha Seva Foundation', 'AAATA1234BE20214')) 'application/pdf'
Doc $asha @{ title = 'Trust registration certificate'; category = 'LEGAL_REGISTRATION'; tags = @('registration'); documentDate = '2015-05-20'; visibility = 'RESTRICTED' } (RealPdf @('Certificate of registration', 'Public trust: Asha Seva Foundation', 'TEST/0001/2015')) 'application/pdf'
Doc $asha @{ title = 'CSR-1 registration (MCA)'; category = 'CSR'; tags = @('csr-1', 'mca'); documentDate = '2021-06-15'; visibility = 'TEAM' } (RealPdf @('Form CSR-1 - registration of entity', 'Asha Seva Foundation', 'CSR registration no. CSR00012345')) 'application/pdf'
Doc $asha @{ title = 'Board of trustees 2026'; category = 'CSR'; tags = @('board', 'trustees'); documentDate = '2026-04-10'; visibility = 'TEAM' } (RealPdf @('Board of trustees', '1. Asha Patil - Chairman', '2. Ravi Kulkarni - Secretary', '3. Priya Deshmukh - Treasurer')) 'application/pdf'
Doc $asha @{ title = 'Annual report 2025-26'; category = 'PHOTOS_REPORTS'; tags = @('annual-report'); documentDate = '2026-09-15'; visibility = 'TEAM' } (RealPdf @('Annual report 2025-26', 'Asha Seva Foundation', '1,240 children supported')) 'application/pdf'
# Registration papers are for administrators only (an earlier version of this script shared it with the team).
$reg = @((Api GET "${lib}?q=Trust%20registration%20certificate&size=5" $asha).items | Where-Object { $_.title -eq 'Trust registration certificate' -and $_.visibility -eq 'TEAM' })
foreach ($d in $reg) {
  $null = Api PUT "$lib/$($d.id)" $asha @{ title = $d.title; category = $d.category; tags = $d.tags; visibility = 'RESTRICTED'; documentDate = $d.documentDate; version = $d.version }
  "made admin-only: $($d.title)"
}
'seed-csr: done'
