# DEV/TEST ONLY: end-to-end campaign accounts through the gateway with the standard dataset:
#   admins Asha (primary) + Ravi (secondary), staff Sunil, finance Priya, donor Meera.
# Budget, vendors (+ CSV import), expenses with bills, approval / rejection / void, staff advance ledger,
# notifications, account sheet (JSON, PDF, Excel) and archive rules. Re-runnable: everything is "E2E ..." and cleaned up.
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
    $r = Invoke-WebRequest "$api$path" -Headers @{ Authorization = "Bearer $tok" } -UseBasicParsing
    $ms = New-Object IO.MemoryStream; $r.RawContentStream.CopyTo($ms)
    [pscustomobject]@{ status = [int]$r.StatusCode; type = $r.Headers['Content-Type']; bytes = $ms.ToArray(); disp = $r.Headers['Content-Disposition'] }
  } catch { [pscustomobject]@{ status = [int]$_.Exception.Response.StatusCode; bytes = @() } }
}
function Inbox($tok, $template, $link) {
  $r = Api GET '/api/v1/notifications?size=50' $tok
  @($r.items | Where-Object { $_.templateCode -eq $template -and $_.link -eq $link })
}
function Day($n) { (Get-Date).AddDays($n).ToString('yyyy-MM-dd') }

& $psql -w -h localhost -U postgres -d campaign -q -f "$PSScriptRoot\sql\cleanup-e2e-campaigns.sql" | Out-Null

$asha = Token 'asha.admin@ngo.test' 'DevNgo#2026'
$ravi = Token 'ravi.staff@ngo.test' 'DevStaff#2026'
$sunil = Token 'sunil.staff@ngo.test' 'DevTest#2026'
$priya = Token 'priya.admin@ngo.test' 'DevTest#2026'
$meera = Token 'meera.donor@ngo.test' 'DevDonor#2026'
$team = @((Api GET "/api/v1/ngos/$NgoId/staff" $asha) | ForEach-Object { $_ })
function Uid($email) { @($team | Where-Object { $_.email -eq $email })[0].userId }
$sunilId = Uid 'sunil.staff@ngo.test'; $raviId = Uid 'ravi.staff@ngo.test'
Check 'team ids resolved' ($sunilId -and $raviId) (J $team)

# --- live E2E campaign ---
$base = "/api/v1/ngos/$NgoId/campaigns"
$camp = Api POST $base $asha @{ title = 'E2E Accounts test campaign'; summary = 'Used by the campaign accounts end-to-end test only.'; cause = 'EDUCATION'; goalAmount = '50000'; startDate = (Day -10); endDate = (Day 30) }
$camp = Api POST "$base/$($camp.id)/approve" $asha @{ version = $camp.version }
Check 'E2E campaign is live' ($camp.status -eq 'PUBLISHED') (J $camp)
$cb = "$base/$($camp.id)"

# --- budget ---
$b = Api PUT "$cb/budget" $asha @{ lines = @(@{ category = 'Books'; planned = '30000' }, @{ category = 'Bags'; planned = '15000' }, @{ category = 'Transport'; planned = '5000' }) }
$lines = @($b.lines | ForEach-Object { $_ })
Check 'Asha sets a 3-line budget' ($lines.Count -eq 3 -and $lines[0].category -eq 'Books') (J $b)
$books = $lines[0].id; $transport = $lines[2].id
$x = Api PUT "$cb/budget" $sunil @{ lines = @(@{ category = 'Books'; planned = '1' }) }
Check 'staff cannot change the budget of a live campaign (403)' ($x.__status -eq 403 -and $x.__code -eq 'ADMIN_REQUIRED') (J $x)
$x = Api PUT "$cb/budget" $asha @{ lines = @(@{ category = 'Books'; planned = '1' }, @{ category = 'books '; planned = '2' }) }
Check 'duplicate budget category refused' ($x.__code -eq 'DUPLICATE_CATEGORY') (J $x)

# --- vendors ---
$v = Api POST "/api/v1/ngos/$NgoId/vendors" $sunil @{ name = 'E2E Sharma Stationers'; phone = '98765 43210'; gstin = '27abcde1234f1z5'; category = 'Stationery' }
Check 'staff adds a vendor (GSTIN normalised)' ($v.id -and $v.gstin -eq '27ABCDE1234F1Z5' -and $v.phone -eq '9876543210') (J $v)
$x = Api POST "/api/v1/ngos/$NgoId/vendors" $asha @{ name = 'e2e sharma stationers' }
Check 'duplicate vendor name refused (409)' ($x.__status -eq 409 -and $x.__code -eq 'VENDOR_EXISTS') (J $x)
$x = Api POST "/api/v1/ngos/$NgoId/vendors" $asha @{ name = 'E2E Bad Tax'; gstin = '12345' }
Check 'invalid GSTIN refused' ($x.__code -eq 'INVALID_GSTIN') (J $x)
$csv = "name,phone,category`r`nE2E Patil Transport,9822012345,Transport`r`nE2E Sharma Stationers,,`r`nE2E Bad Phone,12345,`r`n"
$imp = Multi "/api/v1/ngos/$NgoId/vendors/import" $asha $null $null 'file' @(, [Text.Encoding]::UTF8.GetBytes($csv)) 'text/csv'
Check 'CSV import: 1 created, 1 duplicate skipped, 1 error with its line' ($imp.created -eq 1 -and $imp.skipped -eq 1 -and @($imp.errors).Count -eq 1 -and $imp.errors[0].line -eq 4) (J $imp)
$vl = @((Api GET "/api/v1/ngos/$NgoId/vendors?q=e2e" $sunil) | ForEach-Object { $_ })
Check 'vendor search finds both E2E vendors' ($vl.Count -eq 2) (J $vl)
$x = Api GET "/api/v1/ngos/$NgoId/vendors" $meera
Check 'donor cannot see the vendor list (403)' ($x.__status -eq 403) (J $x)
$tpl = Get-Bytes "/api/v1/ngos/$NgoId/vendors/template.csv" $sunil
Check 'CSV template downloads' ($tpl.status -eq 200 -and [Text.Encoding]::UTF8.GetString($tpl.bytes).StartsWith('name,phone,email,gstin')) ''

# --- expenses ---
$e1 = Multi "$cb/expenses" $sunil 'expense' @{ budgetLineId = $books; description = 'E2E 200 notebooks'; amount = '4500'; date = (Day -2); vendorId = $v.id; billNumber = 'SS-1234'; paymentMode = 'UPI'; paidBy = 'NGO' } 'files' @(, (Png 900 1200))
Check 'Sunil adds a ₹4,500 expense with a bill photo' ($e1.__status -eq 201 -and $e1.status -eq 'SUBMITTED' -and $e1.attachmentCount -eq 1 -and $e1.category -eq 'Books' -and $e1.vendorName -eq 'E2E Sharma Stationers') (J $e1)
$att = Get-Bytes "$cb/expenses/$($e1.id)/attachments/$($e1.attachments[0].id)" $asha
Check 'bill is stored re-encoded as JPEG (no metadata)' ($att.status -eq 200 -and $att.type -like 'image/jpeg*' -and $att.bytes[0] -eq 0xFF -and $att.bytes[1] -eq 0xD8) "$($att.status) $($att.type)"
$x = Get-Bytes "$cb/expenses/$($e1.id)/attachments/$($e1.attachments[0].id)" $meera
Check 'donor cannot open bills (403)' ($x.status -eq 403) ''
$x = Api POST "$cb/expenses" $sunil @{ description = 'E2E no bill'; amount = '100'; date = (Day 0); paymentMode = 'CASH'; paidBy = 'NGO' }
Check 'expense without bill or reason refused' ($x.__code -eq 'BILL_REQUIRED') (J $x)
$x = Api POST "$cb/expenses" $sunil @{ description = 'E2E future'; amount = '100'; date = (Day 2); paymentMode = 'CASH'; paidBy = 'NGO'; noBillReason = 'Testing dates' }
Check 'future bill date refused' ($x.__code -eq 'DATE_IN_FUTURE') (J $x)
$x = Api POST "$cb/expenses" $sunil @{ description = 'E2E someone else paid'; amount = '100'; date = (Day 0); paymentMode = 'CASH'; paidBy = 'STAFF'; paidByUser = $raviId; noBillReason = 'Testing payer' }
Check 'staff cannot record money paid by someone else (403)' ($x.__status -eq 403 -and $x.__code -eq 'PAYER_NOT_SELF') (J $x)
$e2 = Api POST "$cb/expenses" $sunil @{ budgetLineId = $transport; description = 'E2E auto to the school'; amount = '350'; date = (Day -1); vendorName = 'Auto-rickshaw'; paymentMode = 'CASH'; paidBy = 'STAFF'; noBillReason = 'Auto fare, no receipt given' }
Check 'Sunil records ₹350 he paid himself (no-bill reason)' ($e2.status -eq 'SUBMITTED' -and $e2.paidBy -eq 'STAFF' -and $e2.paidByUser -eq $sunilId) (J $e2)

$link1 = "/ngo/campaigns/$($camp.id)/expenses/$($e1.id)"
$n = @(WaitFor { Inbox $ravi 'EXPENSE_SUBMITTED' $link1 } 60)
Check 'Ravi (admin) is asked to approve, with a link to the expense' ($n.Count -ge 1) ''
Check 'Asha (admin) is asked too' (@(WaitFor { Inbox $asha 'EXPENSE_SUBMITTED' $link1 } 30).Count -ge 1) ''
Check 'Sunil is not notified of his own expense' ((Inbox $sunil 'EXPENSE_SUBMITTED' $link1).Count -eq 0) ''
$pend = Api GET "/api/v1/ngos/$NgoId/expenses/pending" $asha
Check 'approval inbox lists both with the campaign title' ($pend.count -ge 2 -and @($pend.items | Where-Object { $_.campaignTitle -eq 'E2E Accounts test campaign' }).Count -eq 2) (J $pend)

$x = Api POST "$cb/expenses/$($e1.id)/approve" $sunil @{ version = $e1.version }
Check 'staff cannot approve (403)' ($x.__status -eq 403) (J $x)
$x = Api POST "$cb/expenses/$($e1.id)/approve" $priya @{ version = $e1.version }
Check 'finance staff cannot approve either: admins only (403)' ($x.__status -eq 403 -and $x.__code -eq 'ADMIN_REQUIRED') (J $x)
$x = Api POST "$cb/expenses/$($e1.id)/approve" $asha @{ version = ($e1.version + 5) }
Check 'approving a stale version refused (409)' ($x.__status -eq 409) (J $x)
$a1 = Api POST "$cb/expenses/$($e1.id)/approve" $asha @{ version = $e1.version }
Check 'Asha approves: voucher number EXP/<FY>/nnnn' ($a1.status -eq 'APPROVED' -and $a1.voucherNo -match '^EXP/\d{4}-\d{2}/\d{4}$') (J $a1)
Check 'Sunil is told it was approved' (@(WaitFor { Inbox $sunil 'EXPENSE_APPROVED' $link1 } 60).Count -ge 1) ''
$x = Api PUT "$cb/expenses/$($e1.id)" $sunil @{ description = 'E2E changed'; amount = '1'; date = (Day -2); paymentMode = 'UPI'; paidBy = 'NGO'; version = $a1.version }
Check 'approved expense cannot be edited' ($x.__code -eq 'EXPENSE_LOCKED') (J $x)
$x = Api DELETE "$cb/expenses/$($e1.id)" $sunil
Check 'approved expense cannot be deleted' ($x.__code -eq 'EXPENSE_LOCKED') (J $x)
$x = Multi "$cb/expenses/$($e1.id)/attachments" $sunil $null $null 'file' @(, (Png 600 800))
Check 'no bills can be added after approval' ($x.code -eq 'EXPENSE_LOCKED') (J $x)

$r2 = Api POST "$cb/expenses/$($e2.id)/reject" $asha @{ reason = 'E2E please add a photo of the auto meter' }
Check 'Asha sends the auto fare back with a reason' ($r2.status -eq 'REJECTED' -and $r2.rejectionReason -like '*auto meter*') (J $r2)
$link2 = "/ngo/campaigns/$($camp.id)/expenses/$($e2.id)"
Check 'Sunil sees the reason in the app' (@(WaitFor { Inbox $sunil 'EXPENSE_REJECTED' $link2 } 60).Count -ge 1) ''
$mail = WaitFor { @((Invoke-RestMethod "$mailpit/search?query=to:sunil.staff@ngo.test").messages | Where-Object { $_.Subject -like 'Expense needs changes*' }) } 60
Check 'Sunil gets an e-mail about it' (@($mail).Count -ge 1) ''
$x = Multi "$cb/expenses/$($e2.id)/attachments" $sunil $null $null 'file' @(, (Png 700 500 'LightBlue'))
Check 'Sunil adds the photo' ($x.attachmentCount -eq 1) (J $x)
$u2 = Api PUT "$cb/expenses/$($e2.id)" $sunil @{ budgetLineId = $transport; description = 'E2E auto to the school'; amount = '350'; date = (Day -1); vendorName = 'Auto-rickshaw'; paymentMode = 'CASH'; paidBy = 'STAFF'; version = $x.version }
Check 'editing sends it back for approval' ($u2.status -eq 'SUBMITTED' -and -not $u2.rejectionReason) (J $u2)
$a2 = Api POST "$cb/expenses/$($e2.id)/approve" $ravi @{ version = $u2.version }
Check 'Ravi (second admin) approves it' ($a2.status -eq 'APPROVED') (J $a2)

$e3 = Api POST "$cb/expenses" $asha @{ budgetLineId = $books; description = 'E2E textbooks set'; amount = '12000'; date = (Day -1); paymentMode = 'BANK_TRANSFER'; paidBy = 'NGO'; noBillReason = 'E2E invoice comes by post' }
$x = Api POST "$cb/expenses/$($e3.id)/approve" $asha @{ version = $e3.version }
Check 'an admin cannot approve her own expense when another admin exists' ($x.__code -eq 'SELF_APPROVAL') (J $x)
$a3 = Api POST "$cb/expenses/$($e3.id)/approve" $ravi @{ version = $e3.version }
Check 'Ravi approves Asha''s expense' ($a3.status -eq 'APPROVED' -and -not $a3.selfApproved) (J $a3)

# --- staff cash ---
$m = Api GET "/api/v1/ngos/$NgoId/staff-money/me" $sunil
$before = [decimal]$m.balance
$adv = Api POST "/api/v1/ngos/$NgoId/staff-money" $asha @{ userId = $sunilId; kind = 'ADVANCE'; amount = '2000'; mode = 'CASH'; campaignId = $camp.id; note = 'E2E cash for books' }
Check 'Asha gives Sunil a ₹2,000 advance' ($adv.kind -eq 'ADVANCE' -and [decimal]$adv.amount -eq -2000) (J $adv)
$x = Api POST "/api/v1/ngos/$NgoId/staff-money" $sunil @{ userId = $sunilId; kind = 'ADVANCE'; amount = '5000'; mode = 'CASH' }
Check 'staff cannot record advances (403)' ($x.__status -eq 403) (J $x)
$m = Api GET "/api/v1/ngos/$NgoId/staff-money/me" $sunil
Check 'Sunil''s balance: +350 spent, -2000 advance' ([decimal]$m.balance -eq $before - 2000) (J $m)
Check 'his ledger shows the approved expense' (@($m.entries | Where-Object { $_.kind -eq 'EXPENSE' -and $_.expenseId -eq $e2.id -and [decimal]$_.amount -eq 350 }).Count -eq 1) (J $m)
$x = Api POST "/api/v1/ngos/$NgoId/staff-money" $asha @{ userId = $sunilId; kind = 'RETURN'; amount = ([string](2000 - $before + 1)); mode = 'CASH'; campaignId = $camp.id; note = 'E2E too much' }
Check 'return larger than the cash he holds is refused' ($x.__code -eq 'RETURN_TOO_LARGE') (J $x)
$x = Api GET "/api/v1/ngos/$NgoId/staff-money" $sunil
Check 'only admins see everyone''s balances (403)' ($x.__status -eq 403) (J $x)
$x = Api GET "/api/v1/ngos/$NgoId/staff-money/$raviId" $sunil
Check 'staff cannot see a colleague''s ledger (403)' ($x.__status -eq 403) (J $x)
$all = @((Api GET "/api/v1/ngos/$NgoId/staff-money" $ravi) | ForEach-Object { $_ })
Check 'admin list has Sunil' (@($all | Where-Object { $_.userId -eq $sunilId }).Count -eq 1) (J $all)

# --- money in + account sheet ---
$null = Api POST "$cb/test-donations" $sunil @{ amount = '20000'; donorLabel = 'E2E donor' }
$s = Api GET "$cb/accounts" $sunil
$bk = @($s.budget | Where-Object { $_.category -eq 'Books' })[0]
$tr = @($s.budget | Where-Object { $_.category -eq 'Transport' })[0]
Check 'sheet: received 20,000, spent 16,850, balance 3,150' ([decimal]$s.income.net -eq 20000 -and [decimal]$s.totals.spent -eq 16850 -and [decimal]$s.totals.balance -eq 3150) (J $s.totals)
Check 'sheet: budget vs actual per category' ([decimal]$bk.spent -eq 16500 -and [decimal]$bk.remaining -eq 13500 -and [decimal]$tr.spent -eq 350 -and $bk.count -eq 2) (J $s.budget)
Check 'sheet: 3 approved vouchers, provisional' (@($s.expenses).Count -eq 3 -and -not $s.finalSheet -and $s.generatedBy -like 'Sunil*') ''
$sr = @($s.staff | Where-Object { $_.userId -eq $sunilId })[0]
Check 'sheet: Sunil holds 1,650 for this campaign' ([decimal]$sr.advanced -eq 2000 -and [decimal]$sr.spent -eq 350 -and [decimal]$sr.balance -eq -1650) (J $s.staff)
$pdf = Get-Bytes "$cb/accounts.pdf" $asha
Check 'account sheet PDF downloads' ($pdf.status -eq 200 -and $pdf.type -like 'application/pdf*' -and [Text.Encoding]::ASCII.GetString($pdf.bytes, 0, 5) -eq '%PDF-' -and $pdf.disp -like '*account-sheet-*') "$($pdf.status) $($pdf.type)"
$xl = Get-Bytes "$cb/accounts.xlsx" $asha
Check 'account sheet Excel downloads' ($xl.status -eq 200 -and $xl.bytes[0] -eq 0x50 -and $xl.bytes[1] -eq 0x4B) "$($xl.status) $($xl.type)"
New-Item -ItemType Directory -Force "$PSScriptRoot\..\.agent" | Out-Null
[IO.File]::WriteAllBytes("$PSScriptRoot\..\.agent\e2e-account-sheet.pdf", $pdf.bytes)
[IO.File]::WriteAllBytes("$PSScriptRoot\..\.agent\e2e-account-sheet.xlsx", $xl.bytes)
$x = Get-Bytes "$cb/accounts.pdf" $meera
Check 'donor cannot download the account sheet (403)' ($x.status -eq 403) ''

# --- void reverses ---
$v2 = Api POST "$cb/expenses/$($e2.id)/void" $asha @{ reason = 'E2E entered against the wrong campaign' }
Check 'Asha voids the auto fare' ($v2.status -eq 'VOID' -and $v2.voucherNo) (J $v2)
$m = Api GET "/api/v1/ngos/$NgoId/staff-money/me" $sunil
Check 'void reverses Sunil''s ledger (-350)' ([decimal]$m.balance -eq $before - 2000 - 350) (J $m.balance)
$s = Api GET "$cb/accounts" $asha
Check 'void leaves the spent total, stays listed as voided' ([decimal]$s.totals.spent -eq 16500 -and @($s.voided).Count -eq 1) (J $s.totals)
$ret = Api POST "/api/v1/ngos/$NgoId/staff-money" $asha @{ userId = $sunilId; kind = 'RETURN'; amount = '2000'; mode = 'CASH'; campaignId = $camp.id; note = 'E2E unused cash back' }
Check 'Sunil returns the unused ₹2,000' ($ret.kind -eq 'RETURN') (J $ret)
$x = Api POST "$cb/expenses/$($e2.id)/void" $asha @{ reason = 'E2E void twice' }
Check 'a void is final' ($x.__code -eq 'EXPENSE_NOT_APPROVED') (J $x)

# --- archive closes the books ---
$e4 = Api POST "$cb/expenses" $sunil @{ description = 'E2E last minute snacks'; amount = '220'; date = (Day 0); paymentMode = 'CASH'; paidBy = 'NGO'; noBillReason = 'E2E street vendor' }
$c = Api POST "$cb/close" $asha
$x = Api POST "$cb/archive" $asha
Check 'cannot archive while an expense waits for approval' ($x.__code -eq 'EXPENSES_PENDING') (J $x)
$x = Api DELETE "$cb/expenses/$($e4.id)" $asha
Check 'only the submitter can withdraw a pending expense' ($x.__code -eq 'NOT_YOUR_EXPENSE') (J $x)
$x = Api DELETE "$cb/expenses/$($e4.id)" $sunil
Check 'Sunil withdraws it' ($x.__status -eq 204) (J $x)
$c = Api POST "$cb/archive" $asha
Check 'Asha archives the campaign' ($c.status -eq 'ARCHIVED') (J $c)
$s = Api GET "$cb/accounts" $asha
Check 'account sheet is now final' ($s.finalSheet -eq $true) ''
$x = Api POST "$cb/expenses" $sunil @{ description = 'E2E after archive'; amount = '10'; date = (Day 0); paymentMode = 'CASH'; paidBy = 'NGO'; noBillReason = 'E2E after archive' }
Check 'no expenses after archive' ($x.__code -eq 'CAMPAIGN_ARCHIVED') (J $x)

# --- cleanup ---
& $psql -w -h localhost -U postgres -d campaign -q -f "$PSScriptRoot\sql\cleanup-e2e-campaigns.sql" | Out-Null
$left = & $psql -w -h localhost -U postgres -d campaign -t -A -c "select count(*) from campaigns where title like 'E2E %'"
Check 'E2E data cleaned up' ("$left".Trim() -eq '0') "$left"
""
"RESULT: $pass passed, $fail failed"
