# DEV/TEST ONLY: sample campaign accounts for the standard dataset (run after seed-campaigns.ps1).
# "School books for 200 children": a budget, 3 vendors, approved expenses with bill photos, one waiting for approval,
# one sent back, and a cash advance to Sunil. Idempotent: does nothing when the campaign already has expenses.
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
# A printed-receipt look-alike (fictional shop) so bill photos are recognisable in the app.
function Bill([string]$shop, [string[]]$items, [string]$total, [string]$no) {
  Add-Type -AssemblyName System.Drawing
  $bmp = New-Object Drawing.Bitmap 900, 1200
  $g = [Drawing.Graphics]::FromImage($bmp)
  $g.TextRenderingHint = 'AntiAlias'
  $g.Clear([Drawing.Color]::FromArgb(250, 248, 240))
  $big = New-Object Drawing.Font('Arial', 34, [Drawing.FontStyle]::Bold)
  $f = New-Object Drawing.Font('Consolas', 24)
  $ink = [Drawing.Brushes]::Black
  $g.DrawString($shop, $big, $ink, 60, 60)
  $g.DrawString("Bill No. $no      Date: $((Get-Date).ToString('dd-MM-yyyy'))", $f, $ink, 60, 140)
  $g.DrawString(('-' * 46), $f, $ink, 60, 190)
  $y = 240
  foreach ($i in $items) { $g.DrawString($i, $f, $ink, 60, $y); $y += 50 }
  $g.DrawString(('-' * 46), $f, $ink, 60, $y); $y += 50
  $g.DrawString("TOTAL  Rs. $total", $big, $ink, 60, $y)
  $g.DrawString('Thank you! Visit again', $f, [Drawing.Brushes]::DimGray, 60, 1080)
  $g.Dispose()
  $ms = New-Object IO.MemoryStream; $bmp.Save($ms, [Drawing.Imaging.ImageFormat]::Png); $bmp.Dispose()
  return , $ms.ToArray()
}
function AddExpense($path, $tok, $body, [byte[]]$bill) {
  Add-Type -AssemblyName System.Net.Http
  $c = New-Object Net.Http.HttpClient
  $c.DefaultRequestHeaders.Authorization = New-Object Net.Http.Headers.AuthenticationHeaderValue('Bearer', $tok)
  $form = New-Object Net.Http.MultipartFormDataContent
  $form.Add((New-Object Net.Http.StringContent(($body | ConvertTo-Json -Depth 6), [Text.Encoding]::UTF8, 'application/json')), 'expense')
  if ($bill) {
    $part = New-Object Net.Http.ByteArrayContent(, $bill)
    $part.Headers.ContentType = [Net.Http.Headers.MediaTypeHeaderValue]::Parse('image/png')
    $form.Add($part, 'files', 'bill.png')
  }
  $r = $c.PostAsync("$api$path", $form).Result
  $t = $r.Content.ReadAsStringAsync().Result; $c.Dispose()
  if (-not $r.IsSuccessStatusCode) { throw "expense failed: $([int]$r.StatusCode) $t" }
  $t | ConvertFrom-Json
}
function Day($n) { (Get-Date).AddDays($n).ToString('yyyy-MM-dd') }

$asha = Token 'asha.admin@ngo.test' 'DevNgo#2026'
$ravi = Token 'ravi.staff@ngo.test' 'DevStaff#2026'
$sunil = Token 'sunil.staff@ngo.test' 'DevTest#2026'
$base = "/api/v1/ngos/$NgoId/campaigns"
$books = @((Api GET "${base}?size=100" $asha).items | Where-Object { $_.title -eq 'School books for 200 children' })[0]
if (-not $books) { throw 'Run seed-campaigns.ps1 first' }
$cb = "$base/$($books.id)"
$existing = @((Api GET "$cb/expenses" $asha) | ForEach-Object { $_ })
if ($existing.Count -gt 0) { "exists: School books has $($existing.Count) expenses"; return }

$lines = @((Api PUT "$cb/budget" $asha @{ lines = @(
  @{ category = 'Notebooks'; planned = '20000' }, @{ category = 'Textbooks'; planned = '18000' },
  @{ category = 'School bags'; planned = '10000' }, @{ category = 'Transport'; planned = '2000' }) }).lines | ForEach-Object { $_ })
function Line($name) { @($lines | Where-Object { $_.category -eq $name })[0].id }

$vendors = @{}
foreach ($v in @(
  @{ name = 'Sharma Stationers'; phone = '9876500011'; gstin = '27AAPFS1234K1Z2'; category = 'Stationery'; address = 'Shop 4, Station Road, Wadi' },
  @{ name = 'Pune Book Depot'; phone = '9876500022'; gstin = '27AABCP5678L1Z9'; category = 'Books'; address = 'Appa Balwant Chowk, Pune' },
  @{ name = 'Patil Tempo Service'; phone = '9876500033'; category = 'Transport' })) {
  $have = @((Api GET "/api/v1/ngos/$NgoId/vendors?includeInactive=true" $asha) | Where-Object { $_.name -eq $v.name })
  $vendors[$v.name] = if ($have) { $have[0] } else { Api POST "/api/v1/ngos/$NgoId/vendors" $sunil $v }
}

$team = @((Api GET "/api/v1/ngos/$NgoId/staff" $asha) | ForEach-Object { $_ })
$sunilId = @($team | Where-Object { $_.email -eq 'sunil.staff@ngo.test' })[0].userId
$null = Api POST "/api/v1/ngos/$NgoId/staff-money" $asha @{ userId = $sunilId; kind = 'ADVANCE'; amount = '3000'; mode = 'CASH'; campaignId = $books.id; note = 'Cash for tempo and small purchases'; date = (Day -4) }

$e = AddExpense "$cb/expenses" $sunil @{ budgetLineId = (Line 'Notebooks'); description = '600 long notebooks (6 per child)'; amount = '9600'; date = (Day -4); vendorId = $vendors['Sharma Stationers'].id; billNumber = 'SS/2245'; paymentMode = 'UPI'; paidBy = 'NGO' } (Bill 'SHARMA STATIONERS' @('Long notebook 172 pg   600 x 16', '', '') '9,600.00' 'SS/2245')
$null = Api POST "$cb/expenses/$($e.id)/approve" $asha @{ version = $e.version }
$e = AddExpense "$cb/expenses" $sunil @{ budgetLineId = (Line 'Textbooks'); description = 'Class 1-7 textbook sets (50 sets)'; amount = '8500'; date = (Day -3); vendorId = $vendors['Pune Book Depot'].id; billNumber = 'PBD-8812'; paymentMode = 'BANK_TRANSFER'; paidBy = 'NGO' } (Bill 'PUNE BOOK DEPOT' @('Balbharati set Cl 1-4    30 x 150', 'Balbharati set Cl 5-7    20 x 200') '8,500.00' 'PBD-8812')
$null = Api POST "$cb/expenses/$($e.id)/approve" $ravi @{ version = $e.version }
$e = AddExpense "$cb/expenses" $sunil @{ budgetLineId = (Line 'Transport'); description = 'Tempo: books from Pune to Wadi school'; amount = '1200'; date = (Day -3); vendorId = $vendors['Patil Tempo Service'].id; paymentMode = 'CASH'; paidBy = 'STAFF' } (Bill 'PATIL TEMPO SERVICE' @('Pune - Wadi, 1 trip       1,200') '1,200.00' '77')
$null = Api POST "$cb/expenses/$($e.id)/approve" $asha @{ version = $e.version }
$e = AddExpense "$cb/expenses" $sunil @{ budgetLineId = (Line 'School bags'); description = '40 school bags (first lot)'; amount = '6000'; date = (Day -1); vendorId = $vendors['Sharma Stationers'].id; billNumber = 'SS/2301'; paymentMode = 'UPI'; paidBy = 'NGO' } (Bill 'SHARMA STATIONERS' @('School bag (medium)     40 x 150') '6,000.00' 'SS/2301')
$e = AddExpense "$cb/expenses" $sunil @{ description = 'Tea and snacks for volunteers'; category = 'Food'; amount = '450'; date = (Day -1); vendorName = 'Ganesh Tea Stall'; paymentMode = 'CASH'; paidBy = 'STAFF'; noBillReason = 'Small tea stall, no bill book' } $null
$null = Api POST "$cb/expenses/$($e.id)/reject" $asha @{ reason = 'Food is not in this campaign''s budget. Please claim it under the volunteer fund.' }
$s = Api GET "$cb/accounts" $asha
"School books: received $($s.income.net), spent $($s.totals.spent), pending $($s.totals.pendingCount), balance $($s.totals.balance)"
