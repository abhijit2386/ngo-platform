# DEV/TEST ONLY: receipt load test. Records -Count cash donations for Asha Seva at the same moment (-Threads parallel
# requests), waits until every receipt is issued and checks: one live receipt per donation, receipt numbers
# consecutive with no gaps or repeats, which receipt-service copy produced each one (several copies can run:
# start-local.ps1 -ReceiptCopies 3), and how long it took. Donations are named "E2E Load ..." and removed afterwards.
#   powershell -File scripts\test-load-receipts.ps1 [-Count 75] [-Threads 25] [-Label "3 copies"]
param([string]$NgoId = '0cf945f6-1183-42f5-aeb1-20bab9be302b', [int]$Count = 75, [int]$Threads = 25, [string]$Label = '')
$ErrorActionPreference = 'Stop'
$api = 'http://localhost:8080'; $kc = 'http://localhost:8180/realms/ngo/protocol/openid-connect/token'
$psql = 'D:\tools\pgsql\bin\psql.exe'
$env:PGPASSWORD = 'dev-only-password'
$pass = 0; $fail = 0
function Check([string]$name, [bool]$ok, $detail = '') {
  if ($ok) { $script:pass++; "PASS  $name" } else { $script:fail++; "FAIL  $name  $detail" }
}
function Sql([string]$db, [string]$q) { @(& $psql -w -h localhost -U postgres -d $db -t -A -F '|' -c $q 2>$null | Where-Object { $_ -ne '' }) }
# First line of a query as text. (PowerShell unrolls a one-element array returned by a function into a plain string,
# and indexing a string with [0] gives its first character, so never write (Sql ...)[0].)
function One([string]$db, [string]$q) { [string](@(Sql $db $q) | Select-Object -First 1) }

& powershell -NoProfile -ExecutionPolicy Bypass -File "$PSScriptRoot\cleanup-e2e-donations.ps1" | Out-Null
$asha = (Invoke-RestMethod -Method Post $kc -Body @{ grant_type = 'password'; client_id = 'ngo-admin-cli'; username = 'asha.admin@ngo.test'; password = 'DevNgo#2026'; scope = 'openid' }).access_token
$design = (One 'receipt' "select count(*) from receipt_templates where ngo_id = '$NgoId' and is_active")
"design in use: $(if ($design -eq '1') { 'custom (receipt-book picture)' } else { 'standard' })"

# --- fire all donations at once -------------------------------------------------------------------------------------
$today = (Get-Date).ToString('yyyy-MM-dd')
$pool = [runspacefactory]::CreateRunspacePool(1, $Threads); $pool.Open()
$script = {
  param($api, $tok, $ngo, $i, $today)
  $body = @{ donorName = ('E2E Load donor {0:D3}' -f $i); amount = [string](500 + $i * 10); channel = 'CASH'; receivedOn = $today
    purpose = 'E2E load test'; wants80g = ($i % 3 -eq 0) }
  if ($i % 3 -eq 0) { $body.donorPan = 'ABCDE1234F'; $body.donorEmail = "load$i@e2e.test" }
  $t0 = Get-Date
  try {
    $r = Invoke-RestMethod -Method Post "$api/api/v1/ngos/$ngo/donations/offline" -Headers @{ Authorization = "Bearer $tok" } `
      -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes(($body | ConvertTo-Json)))
    [pscustomobject]@{ i = $i; id = $r.id; ms = ((Get-Date) - $t0).TotalMilliseconds; error = $null }
  } catch { [pscustomobject]@{ i = $i; id = $null; ms = ((Get-Date) - $t0).TotalMilliseconds; error = "$_" } }
}
$start = Get-Date
$jobs = foreach ($i in 1..$Count) {
  $ps = [powershell]::Create(); $ps.RunspacePool = $pool
  [void]$ps.AddScript($script).AddArgument($api).AddArgument($asha).AddArgument($NgoId).AddArgument($i).AddArgument($today)
  [pscustomobject]@{ ps = $ps; h = $ps.BeginInvoke() }
}
$results = foreach ($j in $jobs) { $j.ps.EndInvoke($j.h); $j.ps.Dispose() }
$pool.Close()
$fired = (Get-Date) - $start
$ok = @($results | Where-Object { $_.id })
Check "$Count donations recorded in parallel ($Threads at a time)" ($ok.Count -eq $Count) (@($results | Where-Object { $_.error } | Select-Object -First 2 | ForEach-Object { $_.error }) -join ' | ')
"  recording took {0:N1} s (slowest request {1:N0} ms)" -f $fired.TotalSeconds, (($results | Measure-Object ms -Maximum).Maximum)

# --- wait until every receipt is issued -----------------------------------------------------------------------------
$ids = ($ok | ForEach-Object { "'$($_.id)'" }) -join ','
$deadline = (Get-Date).AddMinutes(10)
function ReceiptPorts { @(8087, 8097, 8107 | Where-Object { try { $c = New-Object Net.Sockets.TcpClient; $c.Connect('127.0.0.1', $_); $c.Close(); $true } catch { $false } }) }
$before = ReceiptPorts
"  receipt-service copies listening: $($before -join ', ')"
$dead = 0
do {
  Start-Sleep 2
  $issued = [int](One 'receipt' "select count(*) from donation_receipts where donation_id in ($ids) and pdf_key is not null")
  $alive = ReceiptPorts
  if ($alive.Count -lt $before.Count) { $dead++ } else { $dead = 0 }
  if ($dead -ge 3) {
    "  CRASHED: receipt-service copies listening now: $(if ($alive.Count) { $alive -join ', ' } else { 'none' }) (were $($before -join ', '))"
    Get-WinEvent -FilterHashtable @{LogName='Application'; StartTime=$start} -ErrorAction SilentlyContinue | Where-Object { $_.Id -eq 1000 -and $_.Message -match 'java' } |
      Select-Object -First 2 | ForEach-Object { "  {0}  {1}" -f $_.TimeCreated, (($_.Message -split "`n" | Select-String 'Exception code|Fault offset|Faulting process id') -join ' | ') }
    if ($alive.Count -eq 0) { break }
    $before = $alive; $dead = 0
  }
} while ($issued -lt $ok.Count -and (Get-Date) -lt $deadline)
$total = (Get-Date) - $start
Check "every receipt issued" ($issued -eq $ok.Count) "$issued of $($ok.Count)"
"  all receipts issued {0:N1} s after the first donation" -f $total.TotalSeconds

# --- correctness ----------------------------------------------------------------------------------------------------
$dupes = [int](One 'receipt' "select count(*) from (select donation_id from donation_receipts where donation_id in ($ids) and status not in ('VOIDED','REISSUED') group by donation_id having count(*) > 1) x")
Check 'exactly one receipt per donation' ($dupes -eq 0) "$dupes donations with more than one"
$nums = @(Sql 'receipt' "select split_part(receipt_number, '/', 3)::int from donation_receipts where donation_id in ($ids) and receipt_number is not null order by 1" | ForEach-Object { [int]$_ })
$distinct = @($nums | Select-Object -Unique).Count
$span = if ($nums.Count) { $nums[-1] - $nums[0] + 1 } else { 0 }
Check 'receipt numbers consecutive: no gaps, no repeats' ($nums.Count -eq $ok.Count -and $distinct -eq $nums.Count -and $span -eq $nums.Count) "count=$($nums.Count) distinct=$distinct span=$span"
$failed = [int](One 'receipt' "select count(*) from receipt_events e join donation_receipts r on r.id = e.receipt_id where r.donation_id in ($ids) and e.event = 'GENERATION_FAILED'")
Check 'no generation errors' ($failed -eq 0) "$failed failures"

# --- who did the work, how fast ---------------------------------------------------------------------------------------
"  receipts made per copy:"
Sql 'receipt' "select split_part(e.detail, ' on ', 2), count(*) from receipt_events e join donation_receipts r on r.id = e.receipt_id where r.donation_id in ($ids) and e.event = 'GENERATED' group by 1 order by 1" |
  ForEach-Object { $p = $_.Split('|'); "    {0,-28} {1,4}" -f $p[0], $p[1] }
$stats = (One 'receipt' "select round(extract(epoch from (max(g.created_at) - min(c.created_at)))::numeric, 1), round(avg(extract(epoch from (g.created_at - c.created_at)))::numeric, 1), round(max(extract(epoch from (g.created_at - c.created_at)))::numeric, 1) from donation_receipts r join receipt_events c on c.receipt_id = r.id and c.event = 'CREATED' join receipt_events g on g.receipt_id = r.id and g.event = 'GENERATED' where r.donation_id in ($ids)").Split('|')
"  first receipt created -> last generated: $($stats[0]) s; wait per receipt: average $($stats[1]) s, longest $($stats[2]) s"
$copiesUsed = @(Sql 'receipt' "select distinct split_part(e.detail, ' on ', 2) from receipt_events e join donation_receipts r on r.id = e.receipt_id where r.donation_id in ($ids) and e.event = 'GENERATED'").Count
"  copies that generated receipts: $copiesUsed"
"SUMMARY $Label | donations=$($ok.Count) | issued=$issued | copies=$copiesUsed | created->last generated=$($stats[0]) s | avg wait=$($stats[1]) s | max wait=$($stats[2]) s"

if ($failed -gt 0 -or $issued -lt $ok.Count) {
  "  errors seen (top 5):"
  Sql 'receipt' "select count(*), left(e.detail, 160) from receipt_events e join donation_receipts r on r.id = e.receipt_id where r.donation_id in ($ids) and e.event = 'GENERATION_FAILED' group by 2 order by 1 desc limit 5" | ForEach-Object { "    $_" }
  "  receipts by status:"
  Sql 'receipt' "select status, count(*) from donation_receipts where donation_id in ($ids) group by 1" | ForEach-Object { "    $_" }
  $made = [int](One 'receipt' "select count(*) from donation_receipts where donation_id in ($ids)")
  "  receipt records created: $made of $($ok.Count) (the rest: 'donation paid' event not yet delivered)"
}
& powershell -NoProfile -ExecutionPolicy Bypass -File "$PSScriptRoot\cleanup-e2e-donations.ps1" | Out-Null
"RESULT: $pass passed, $fail failed"
