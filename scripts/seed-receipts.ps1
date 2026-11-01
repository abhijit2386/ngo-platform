# DEV/TEST ONLY: receipt settings for the standard dataset (idempotent): Asha Seva Foundation prints receipts as
# ASF/<FY>/nnnnnn with its 12A number and a thank-you line, signed by Asha Patil (Chairman) and Ravi Kulkarni (Secretary).
# Receipts for donations made earlier are created automatically by receipt-service (backfill).
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
    $r = Invoke-WebRequest "$api$path" -Headers @{ Authorization = "Bearer $tok" } -UseBasicParsing
    $ms = New-Object IO.MemoryStream; $r.RawContentStream.CopyTo($ms)
    [pscustomobject]@{ status = [int]$r.StatusCode; type = $r.Headers['Content-Type']; bytes = $ms.ToArray(); disp = $r.Headers['Content-Disposition'] }
  } catch { [pscustomobject]@{ status = [int]$_.Exception.Response.StatusCode; bytes = @() } }
}
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


$asha = Token 'asha.admin@ngo.test' 'DevNgo#2026'
$rb = "/api/v1/ngos/$NgoId/receipts"
$st = Api GET "$rb/settings" $asha
if (-not $st.saved -or $st.prefix -ne 'ASF') {
  $st = Api PUT "$rb/settings" $asha @{ prefix = 'ASF'; reg12aNumber = 'AAATA1234BE20214'; thankYouNote = 'Thank you for standing with the children of Wadi.'; version = $st.version }
  "settings saved: $($st.prefix)"
} else { "settings exist: $($st.prefix)" }
$have = @((Api GET "$rb/signatories" $asha) | ForEach-Object { $_ })
foreach ($p in @(@{ name = 'Asha Patil'; designation = 'Chairman' }, @{ name = 'Ravi Kulkarni'; designation = 'Secretary' })) {
  $s = @($have | Where-Object { $_.name -eq $p.name })[0]
  if (-not $s) { $s = Api POST "$rb/signatories" $asha $p; "signatory added: $($p.name)" } else { "signatory exists: $($p.name)" }
  if (-not $s.hasSignature) {
    $r = Multi "$rb/signatories/$($s.id)/signature" $asha $null $null 'file' @(, (SignaturePng)) 'image/png'
    "signature uploaded: $($p.name) ($($r.__status))"
  }
}
"seed-receipts: done"
