# DEV/TEST ONLY: end-to-end reports (reporting-service) with the standard dataset. Read-only: every figure the API
# returns is compared with the same figure computed straight from the service databases.
# Owner decisions (2 Nov 2026): NGO-wide reports for admins (Asha, Ravi) and Finance (Priya); everyone else with report
# access (Sunil) gets My report. Plan levels: Community = BASIC, Growth = STANDARD, Professional/Enterprise = ADVANCED.
# Needs: seed-dataset.ps1, seed-campaigns.ps1, seed-donations.ps1 (incl. staff cash/cheque), seed-expenses.ps1. Switches the trial plan and back.
param([string]$NgoId = '0cf945f6-1183-42f5-aeb1-20bab9be302b')
$ErrorActionPreference = 'Stop'
$api = 'http://localhost:8080'; $kc = 'http://localhost:8180/realms/ngo/protocol/openid-connect/token'
$psql = 'D:\tools\pgsql\bin\psql.exe'
$env:PGPASSWORD = 'dev-only-password'
$out = 'D:\ngo-platform\.agent'
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
# Downloads a file; returns status, content type and the first bytes.
function Download($path, $tok, $save = $null) {
  try {
    $r = Invoke-WebRequest -Uri "$api$path" -Headers @{ Authorization = "Bearer $tok" } -UseBasicParsing
    $ms = New-Object IO.MemoryStream; $r.RawContentStream.CopyTo($ms); $b = $ms.ToArray()
    if ($save) { [IO.File]::WriteAllBytes($save, $b) }
    [pscustomobject]@{ Status = [int]$r.StatusCode; Type = [string]$r.Headers['Content-Type']; Head = [Text.Encoding]::ASCII.GetString($b, 0, [Math]::Min(4, $b.Length)); Size = $b.Length }
  } catch {
    $s = 0; if ($_.Exception.Response) { $s = [int]$_.Exception.Response.StatusCode }
    [pscustomobject]@{ Status = $s; Type = ''; Head = ''; Size = 0 }
  }
}
function J($o) { $o | ConvertTo-Json -Compress -Depth 5 }
function Sql([string]$db, [string]$q) { @(& $psql -w -h localhost -U postgres -d $db -t -A -F '|' -c $q 2>$null | Where-Object { $_ -ne '' }) }
function One([string]$db, [string]$q) { [string](@(Sql $db $q) | Select-Object -First 1) }
function Dec($v) { if ($null -eq $v -or "$v" -eq '') { return [decimal]0 }; [decimal]::Round([decimal]::Parse("$v", [Globalization.CultureInfo]::InvariantCulture), 2) }
function Plan($p) { $null = Api PUT "/api/v1/ngos/$NgoId/subscription/trial-plan" $asha @{ plan = $p }; Start-Sleep 32 } # entitlement cache: 30 s

$asha = Token 'asha.admin@ngo.test' 'DevNgo#2026'
$ravi = Token 'ravi.staff@ngo.test' 'DevStaff#2026'
$sunil = Token 'sunil.staff@ngo.test' 'DevTest#2026'
$priya = Token 'priya.admin@ngo.test' 'DevTest#2026'
$meera = Token 'meera.donor@ngo.test' 'DevDonor#2026'
$sunilId = One 'identity' "select id from users where email = 'sunil.staff@ngo.test'"

# Current financial year, India time (the API default).
$now = [DateTime]::UtcNow.AddHours(5.5)
$fyStart = if ($now.Month -ge 4) { Get-Date -Year $now.Year -Month 4 -Day 1 } else { Get-Date -Year ($now.Year - 1) -Month 4 -Day 1 }
$fy = $fyStart.ToString('yyyy-MM-dd'); $fyEnd = $fyStart.AddYears(1).ToString('yyyy-MM-dd')
$paid = "ngo_id = '$NgoId' and status in ('PAID','PARTIALLY_REFUNDED') and not voided and paid_at >= timestamp '$fy' at time zone 'Asia/Kolkata' and paid_at < timestamp '$fyEnd' at time zone 'Asia/Kolkata'"
$dbTotal = Dec (One 'donation' "select coalesce(sum(amount - refunded_amount), 0) from donations where $paid")
$dbCount = [int](One 'donation' "select count(*) from donations where $paid")
"FY from ${fy}: $dbCount donations, total $dbTotal (from the database)"

$origPlan = One 'subscription' "select plan_code from ngo_subscriptions where ngo_id = '$NgoId'"
if (-not $origPlan) { $origPlan = 'PROFESSIONAL' }
Plan 'PROFESSIONAL'

"--- admin (Asha), Professional plan"
$d = Api GET "/api/v1/ngos/$NgoId/dashboard" $asha
Check 'admin dashboard is NGO-wide' ($d.scope -eq 'NGO') (J $d)
Check 'dashboard total = database (financial year)' ((Dec $d.donationsTotal) -eq $dbTotal) "$($d.donationsTotal) vs $dbTotal"
Check 'dashboard says ADVANCED with nothing locked' ($d.level -eq 'ADVANCED' -and @($d.locked).Count -eq 0) "$($d.level) $($d.locked)"
$liveDb = [int](One 'campaign' "select count(*) from campaigns where ngo_id = '$NgoId' and status = 'PUBLISHED'")
Check 'active campaigns = database' ([int]$d.activeCampaigns -eq $liveDb) "$($d.activeCampaigns) vs $liveDb"

$s = Api GET "/api/v1/ngos/$NgoId/reports/summary" $asha
Check 'summary total and count = database' ((Dec $s.donations.total) -eq $dbTotal -and [int]$s.donations.count -eq $dbCount) "$($s.donations.total)/$($s.donations.count)"
$m = [decimal]0; foreach ($x in @($s.donations.byMonth)) { $m += Dec $x.amount }
$c = [decimal]0; foreach ($x in @($s.donations.byChannel)) { $c += Dec $x.amount }
$k = [decimal]0; foreach ($x in @($s.donations.byCampaign)) { $k += Dec $x.amount }
Check 'by month, by mode and by campaign each add up to the total' ($m -eq $dbTotal -and $c -eq $dbTotal -and $k -eq $dbTotal) "$m $c $k"
$cashDb = Dec (One 'donation' "select coalesce(sum(amount - refunded_amount), 0) from donations where $paid and channel = 'CASH'")
$cashApi = Dec ((@($s.donations.byChannel) | Where-Object { $_.key -eq 'CASH' } | Select-Object -First 1).amount)
Check 'cash donations = database' ($cashApi -eq $cashDb) "$cashApi vs $cashDb"
Check 'top donors are names only (no e-mails)' (@($s.donors.top).Count -gt 0 -and -not (@($s.donors.top) | Where-Object { $_.key -or "$($_.label)" -match '@' })) (J $s.donors.top)
$sunilBal = Dec (One 'campaign' "select coalesce(sum(amount), 0) from staff_money_entries where ngo_id = '$NgoId' and user_id = '$sunilId'")
$sunilRow = @($s.staffCash) | Where-Object { $_.userId -eq $sunilId } | Select-Object -First 1
$expectHolds = if ($sunilBal -lt 0) { -$sunilBal } else { [decimal]0 }
Check 'staff cash: what Sunil holds = his ledger' ($sunilRow -and (Dec $sunilRow.holds) -eq $expectHolds -and $sunilRow.name -match 'Sunil') (J $sunilRow)
$pendDb = [int](One 'campaign' "select count(*) from campaign_expenses where ngo_id = '$NgoId' and status = 'SUBMITTED'")
Check 'expense claims waiting = database' ([int]$s.pending.expenses -eq $pendDb) "$($s.pending.expenses) vs $pendDb"
$camp = @($s.campaigns) | Select-Object -First 1
if ($camp) {
  $spentDb = Dec (One 'campaign' "select coalesce(sum(amount), 0) from campaign_expenses where campaign_id = '$($camp.id)' and status = 'APPROVED'")
  Check "campaign '$($camp.title)': spent = approved expenses" ((Dec $camp.spent) -eq $spentDb) "$($camp.spent) vs $spentDb"
}

$aprDb = Dec (One 'donation' ("select coalesce(sum(amount - refunded_amount), 0) from donations where ngo_id = '$NgoId' and status in ('PAID','PARTIALLY_REFUNDED') and not voided " +
  "and paid_at >= timestamp '$fy' at time zone 'Asia/Kolkata' and paid_at < (timestamp '$fy' + interval '1 month') at time zone 'Asia/Kolkata'"))
$apr = Api GET "/api/v1/ngos/$NgoId/reports/summary?from=$fy&to=$($fyStart.AddMonths(1).AddDays(-1).ToString('yyyy-MM-dd'))" $asha
Check 'custom period (first month) = database' ((Dec $apr.donations.total) -eq $aprDb) "$($apr.donations.total) vs $aprDb"
$bad = Api GET "/api/v1/ngos/$NgoId/reports/summary?from=2026-05-01&to=2026-04-01" $asha
Check 'reversed dates are refused (400 BAD_PERIOD)' ($bad.__status -eq 400 -and $bad.__code -eq 'BAD_PERIOD') "$($bad.__status) $($bad.__code)"

"--- 80G register / Form 10BD (Asha)"
$t = Api GET "/api/v1/ngos/$NgoId/reports/80g" $asha
$g80Db = [int](One 'donation' "select count(*) from donations where $paid and wants_80g")
$g80Total = Dec (One 'donation' "select coalesce(sum(amount - refunded_amount), 0) from donations where $paid and wants_80g")
Check '80G register lines and total = database' ([int]$t.donations -eq $g80Db -and (Dec $t.total) -eq $g80Total) "$($t.donations)/$($t.total) vs $g80Db/$g80Total"
$withReceipt = @($t.register | Where-Object { $_.receiptNumber }).Count
Check '80G register carries receipt numbers' ($g80Db -eq 0 -or $withReceipt -gt 0) "$withReceipt of $g80Db"
$sum10 = [decimal]0; foreach ($x in @($t.form10bd)) { $sum10 += Dec $x.amount }
Check 'Form 10BD lines add up to the register' ($sum10 -eq $g80Total) "$sum10 vs $g80Total"
$notes = (@($t.form10bd) | ForEach-Object { $_.note }) -join ' | '
Check 'Form 10BD flags cash above Rs. 2,000' ($notes -match 'above Rs. 2,000') $notes
$cashLine = @($t.form10bd) | Where-Object { $_.idNumber -eq 'ABCPK1234L' } | Select-Object -First 1
Check 'cash Rs. 1,500 with PAN is a clean 10BD line' ($cashLine -and $cashLine.mode -eq 'Cash' -and -not $cashLine.note) (J $cashLine)
$x = Download "/api/v1/ngos/$NgoId/reports/80g.xlsx" $asha "$out\e2e-80g-form10bd.xlsx"
Check '80G / 10BD Excel downloads' ($x.Status -eq 200 -and $x.Head.StartsWith('PK')) "$($x.Status) $($x.Head)"
$aud = [int](One 'reporting' "select count(*) from audit_events where action = 'TAX_REGISTER_EXPORTED' and created_at > now() - interval '5 minutes'")
Check 'the 80G export is audited' ($aud -ge 1) "$aud"

"--- Finance (Priya) and second admin (Ravi)"
$d = Api GET "/api/v1/ngos/$NgoId/dashboard" $priya
Check 'Finance dashboard is NGO-wide and can export' ($d.scope -eq 'NGO' -and $d.canExport -eq $true) (J $d)
$x = Download "/api/v1/ngos/$NgoId/reports/summary.pdf" $priya "$out\e2e-ngo-report.pdf"
Check 'Finance downloads the PDF' ($x.Status -eq 200 -and $x.Head -eq '%PDF') "$($x.Status) $($x.Head)"
$x = Download "/api/v1/ngos/$NgoId/reports/summary.xlsx" $ravi "$out\e2e-ngo-report.xlsx"
Check 'second admin downloads the Excel' ($x.Status -eq 200 -and $x.Head.StartsWith('PK')) "$($x.Status) $($x.Head)"

"--- staff (Sunil): My report only"
$d = Api GET "/api/v1/ngos/$NgoId/dashboard" $sunil
Check 'staff dashboard is "my" scope' ($d.scope -eq 'ME' -and $null -eq $d.donationsTotal) (J $d)
$myDb = Dec (One 'donation' "select coalesce(sum(amount - refunded_amount), 0) from donations where $paid and recorded_by = '$sunilId'")
Check 'Sunil has recorded collections in the dataset (seed-donations)' ($myDb -gt 0) "$myDb"
Check 'my collections = what Sunil recorded' ((Dec $d.myCollections) -eq $myDb) "$($d.myCollections) vs $myDb"
Check 'my cash in hand = his ledger' ((Dec $d.cashInHand) -eq $expectHolds) "$($d.cashInHand) vs $expectHolds"
$me = Api GET "/api/v1/ngos/$NgoId/reports/me" $sunil
$claimsDb = [int](One 'campaign' "select count(*) from campaign_expenses where ngo_id = '$NgoId' and submitted_by = '$sunilId' and status = 'SUBMITTED'")
Check 'my pending claims = database' (@($me.pendingClaims).Count -eq $claimsDb) "$(@($me.pendingClaims).Count) vs $claimsDb"
$r = Api GET "/api/v1/ngos/$NgoId/reports/summary" $sunil
Check 'staff cannot open the NGO report (403)' ($r.__status -eq 403 -and $r.__code -eq 'NGO_REPORTS_ADMIN_FINANCE') "$($r.__status) $($r.__code)"
$r = Api GET "/api/v1/ngos/$NgoId/reports/80g" $sunil
Check 'staff cannot open the 80G register (403)' ($r.__status -eq 403) "$($r.__status)"
$x = Download "/api/v1/ngos/$NgoId/reports/summary.pdf" $sunil
Check 'staff cannot export (403)' ($x.Status -eq 403) "$($x.Status)"
$r = Api GET "/api/v1/ngos/$NgoId/dashboard" $meera
Check 'a donor has no reports (403)' ($r.__status -eq 403) "$($r.__status)"

"--- plan levels"
Plan 'GROWTH'
$s = Api GET "/api/v1/ngos/$NgoId/reports/summary" $asha
Check 'Growth: donors, campaigns, staff cash, export; 80G locked' ($s.level -eq 'STANDARD' -and $s.donors -and $null -ne $s.staffCash -and $s.canExport -and (@($s.locked) -contains 'tax80g')) "$($s.level) $($s.locked)"
$r = Api GET "/api/v1/ngos/$NgoId/reports/80g" $asha
Check 'Growth: 80G register needs a higher plan (402)' ($r.__status -eq 402) "$($r.__status) $($r.__code)"
Plan 'COMMUNITY'
$s = Api GET "/api/v1/ngos/$NgoId/reports/summary" $asha
Check 'Community: donations only, no export' ($s.level -eq 'BASIC' -and $null -eq $s.donors -and $null -eq $s.staffCash -and -not $s.canExport -and (Dec $s.donations.total) -eq $dbTotal) "$($s.level)"
$x = Download "/api/v1/ngos/$NgoId/reports/summary.xlsx" $asha
Check 'Community: export needs a higher plan (402)' ($x.Status -eq 402) "$($x.Status)"
$me = Api GET "/api/v1/ngos/$NgoId/reports/me" $sunil
Check 'Community: My report still works' ($null -ne $me.collections) (J $me)
Plan $origPlan
"plan restored: $origPlan"

""
"reports e2e: $pass passed, $fail failed"
