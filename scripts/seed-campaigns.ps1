# DEV/TEST ONLY: sample campaigns for the standard dataset (run after seed-dataset.ps1). Idempotent by title.
# Shows every workflow state in the app:
#   live (staff-created, admin-approved, with cover + test donations), live (admin-published), awaiting approval, draft.
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

$asha = Token 'asha.admin@ngo.test' 'DevNgo#2026'
$sunil = Token 'sunil.staff@ngo.test' 'DevTest#2026'
$priya = Token 'priya.admin@ngo.test' 'DevTest#2026'
$base = "/api/v1/ngos/$NgoId/campaigns"
$existing = @((Api GET "${base}?size=100" $asha).items)
function Day($n) { (Get-Date).AddDays($n).ToString('yyyy-MM-dd') }

$samples = @(
  @{ by = $sunil; state = 'LIVE'; from = '#0B7A4B'; to = '#7BC67E'; gifts = @(@('12500', 'Meera Iyer'), @('5000', ''), @('2100', 'Kiran Joshi'));
     body = @{ title = 'School books for 200 children'; cause = 'EDUCATION'; goalAmount = '50000'; startDate = (Day -5); endDate = (Day 40)
               summary = 'Notebooks, textbooks and school bags for the children of Wadi village school.'
               story = "Every June, families in Wadi struggle to buy books before school reopens.`n`nYour gift gives each child 6 notebooks, a full textbook set and a sturdy bag. Rs 250 covers one child." } }
  @{ by = $asha; state = 'LIVE'; from = '#C2410C'; to = '#FDBA74'; gifts = @(@('3000', 'Anil Shah'), @('5000', ''));
     body = @{ title = 'Free health camp for elders'; cause = 'ELDERLY_CARE'; goalAmount = '25000'; startDate = (Day 0); endDate = (Day 21)
               summary = 'Eye, blood-pressure and diabetes check-ups with free medicines for 150 senior citizens.'
               story = 'Doctors from the district hospital volunteer their Sunday. Funds cover medicines, reading glasses and transport.' } }
  @{ by = $sunil; state = 'PENDING'; from = '#1D4ED8'; to = '#93C5FD'; gifts = @();
     body = @{ title = 'Monsoon relief kits for 80 families'; cause = 'DISASTER_RELIEF'; goalAmount = '120000'; startDate = (Day 2); endDate = (Day 60)
               summary = 'Tarpaulin, dry rations and hygiene kits for families hit by floods.'
               story = 'Each kit (Rs 1,500) feeds a family of four for two weeks and keeps their home dry.' } }
  @{ by = $priya; state = 'DRAFT'; from = $null; gifts = @();
     body = @{ title = 'Clean drinking water for Wadi village'; cause = 'WATER_SANITATION'; goalAmount = '300000'; startDate = (Day 10); endDate = (Day 120)
               summary = 'A community water filter and storage tank for 600 villagers.' } }
)

foreach ($s in $samples) {
  $title = $s.body.title
  if ($existing | Where-Object { $_.title -eq $title }) { "exists: $title"; continue }
  $c = Api POST $base $s.by $s.body
  if ($s.from) { Upload "$base/$($c.id)/image" $s.by (Cover $s.from $s.to) }
  if ($s.state -eq 'PENDING' -or ($s.state -eq 'LIVE' -and $s.by -ne $asha)) { $c = Api POST "$base/$($c.id)/submit" $s.by }
  if ($s.state -eq 'LIVE') {
    $c = Api GET "$base/$($c.id)" $asha
    $c = Api POST "$base/$($c.id)/approve" $asha @{ version = $c.version }
    foreach ($g in $s.gifts) {
      $b = @{ amount = $g[0] }; if ($g[1]) { $b.donorLabel = $g[1] }
      $c = Api POST "$base/$($c.id)/test-donations" $sunil $b
    }
  }
  "created: $title -> $($c.status) raised=$($c.raisedAmount)"
}
# Gallery for the first live sample (idempotent): 4 photos in total.
$books = @((Api GET "${base}?size=100" $asha).items | Where-Object { $_.title -eq 'School books for 200 children' })[0]
if ($books) {
  $full = Api GET "$base/$($books.id)" $asha
  $have = @($full.photoIds).Count
  $extra = @(@('#0E7490', '#67E8F9'), @('#7C3AED', '#C4B5FD'), @('#B45309', '#FCD34D'))
  for ($i = $have; $i -lt 4; $i++) { Upload "$base/$($books.id)/photos" $asha (Cover $extra[$i - 1][0] $extra[$i - 1][1]) }
  "gallery: School books has $([Math]::Max($have, 4)) photos"
}
$sum = Api GET "$base/summary" $asha
"summary: " + ($sum.counts | ConvertTo-Json -Compress)
