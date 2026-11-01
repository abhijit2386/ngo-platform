# DEV/TEST ONLY: end-to-end document vault + meetings through the gateway with the standard dataset:
#   admins Asha + Ravi, staff Sunil, finance Priya, donor Meera.
# Uploads, who-sees-what, search, signed links, recycle bin, activity log, files of other modules (registration
# documents, expense bills), meetings (AGM / board), minutes, resolutions, action items, minutes PDF in the vault.
# Re-runnable: everything is "E2E ..." and cleaned up.
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


function Pdf([string]$text) { , [Text.Encoding]::ASCII.GetBytes("%PDF-1.4`n% $text`n1 0 obj << >> endobj`ntrailer << >>`n%%EOF`n") }
function Ids($page) { @($page.items | ForEach-Object { $_.id }) }
function Iso($days, $hours = 0) { (Get-Date).ToUniversalTime().AddDays($days).AddHours($hours).ToString('yyyy-MM-ddTHH:mm:ssZ') }

& $psql -w -h localhost -U postgres -d document -q -f "$PSScriptRoot\sql\cleanup-e2e-documents.sql" | Out-Null

$asha = Token 'asha.admin@ngo.test' 'DevNgo#2026'
$ravi = Token 'ravi.staff@ngo.test' 'DevStaff#2026'
$sunil = Token 'sunil.staff@ngo.test' 'DevTest#2026'
$priya = Token 'priya.admin@ngo.test' 'DevTest#2026'
$meera = Token 'meera.donor@ngo.test' 'DevDonor#2026'
$team = @((Api GET "/api/v1/ngos/$NgoId/staff" $asha) | ForEach-Object { $_ })
function Uid($email) { @($team | Where-Object { $_.email -eq $email })[0].userId }
$ashaId = Uid 'asha.admin@ngo.test'; $raviId = Uid 'ravi.staff@ngo.test'; $sunilId = Uid 'sunil.staff@ngo.test'; $priyaId = Uid 'priya.admin@ngo.test'
$lib = "/api/v1/ngos/$NgoId/library"

# --- overview, quota, files of other modules ---
$ov = Api GET "$lib/overview" $asha
Check 'overview: storage limit from the plan (Professional trial = 50 GB)' ([long]$ov.limitBytes -eq 50GB) (J $ov)
$before = [long]$ov.usedBytes
$reg = WaitFor { $p = Api GET "${lib}?category=LEGAL_REGISTRATION&size=50" $asha; if (@($p.items).Count -gt 0) { $p } } 150
Check 'registration documents are listed in the vault (backfill)' ($null -ne $reg -and @($reg.items | Where-Object { $_.source -eq 'REGISTRATION' }).Count -ge 1) (J $reg)
# Backfill registers registration documents first, then expense bills; wait for both.
$bills = WaitFor { $p = Api GET "${lib}?category=FINANCE&size=50" $asha; if (@($p.items | Where-Object { $_.source -eq 'EXPENSE_BILL' }).Count -gt 0) { $p } } 60
$bill = @($bills.items | Where-Object { $_.source -eq 'EXPENSE_BILL' })[0]
Check 'expense bills are listed in the vault, linked to their expense' ($null -ne $bill -and $bill.linkType -eq 'EXPENSE' -and $bill.title -like 'Bill: *') (J $bills.total)
$sReg = Api GET "${lib}?category=LEGAL_REGISTRATION" $sunil
Check 'staff do not see registration documents (admins only)' (@($sReg.items).Count -eq 0) (J $sReg.total)
$sBills = Api GET "${lib}?category=FINANCE&size=50" $sunil
Check 'staff who can see expenses see the bills' (@($sBills.items | Where-Object { $_.id -eq $bill.id }).Count -eq 1) (J $sBills.total)
$x = Api DELETE "$lib/$($bill.id)" $asha
Check 'bills are managed in Expenses, not in the vault' ($x.__code -eq 'MANAGED_ELSEWHERE') (J $x)
$x = Api GET "$lib/overview" $meera
Check 'donor cannot open the NGO vault (403)' ($x.__status -eq 403) (J $x)

# --- uploads and who sees what ---
$meta = @{ title = 'E2E Volunteer guidelines 2026'; category = 'OTHER'; tags = @('e2e', 'Guidelines', '#volunteers'); visibility = 'TEAM'; documentDate = (Get-Date).AddDays(-3).ToString('yyyy-MM-dd') }
$d1 = Multi $lib $sunil 'meta' $meta 'file' @(, (Pdf 'guidelines')) 'application/pdf'
Check 'Sunil uploads a PDF shared with the team' ($d1.__status -eq 201 -and $d1.fileType -eq 'PDF' -and $d1.visibility -eq 'TEAM' -and ($d1.tags -join ',') -eq 'e2e,guidelines,volunteers' -and $d1.canManage) (J $d1)
$d2 = Multi $lib $sunil 'meta' @{ title = 'E2E Offer letter Sunil'; category = 'HR'; tags = @('e2e') } 'file' @(, (Png 800 600)) 'image/png'
Check 'HR documents default to "only me and administrators"' ($d2.__status -eq 201 -and $d2.visibility -eq 'RESTRICTED' -and $d2.fileType -eq 'IMAGE') (J $d2)
$x = Multi $lib $sunil 'meta' @{ title = 'E2E bad'; category = 'OTHER' } 'file' @(, [byte[]](0x4D, 0x5A, 0x90, 0, 3, 0)) 'application/octet-stream'
Check 'program files are refused' ($x.code -eq 'FILE_TYPE_NOT_ALLOWED') (J $x)
$x = Multi $lib $meera 'meta' @{ title = 'E2E donor'; category = 'OTHER' } 'file' @(, (Pdf 'x')) 'application/pdf'
Check 'donor cannot upload (403)' ($x.__status -eq 403) (J $x)
$pv = Api GET "${lib}?q=e2e&size=50" $priya
Check 'Priya (finance) sees the team document, not Sunil''s HR file' ((Ids $pv) -contains $d1.id -and -not ((Ids $pv) -contains $d2.id)) (J (Ids $pv))
$av = Api GET "${lib}?q=e2e&size=50" $asha
Check 'Asha (admin) sees both' ((Ids $av) -contains $d1.id -and (Ids $av) -contains $d2.id) (J (Ids $av))
$x = Api GET "$lib/$($d2.id)" $priya
Check 'opening someone else''s restricted document: not found' ($x.__status -eq 404) (J $x)

# --- search ---
Check 'search by name' ((Ids (Api GET "${lib}?q=guidelines" $sunil)) -contains $d1.id) ''
Check 'search by tag' ((Ids (Api GET "${lib}?tag=volunteers" $sunil)) -contains $d1.id) ''
Check 'filter by category + type' ((Ids (Api GET "${lib}?category=OTHER&type=PDF" $sunil)) -contains $d1.id -and -not ((Ids (Api GET "${lib}?category=OTHER&type=IMAGE" $sunil)) -contains $d1.id)) ''
$from = (Get-Date).AddDays(-5).ToString('yyyy-MM-dd'); $to = (Get-Date).AddDays(-2).ToString('yyyy-MM-dd')
Check 'filter by document date range' ((Ids (Api GET "${lib}?from=$from&to=$to&q=e2e" $sunil)) -contains $d1.id -and -not ((Ids (Api GET "${lib}?from=$from&to=$to&q=e2e" $asha)) -contains $d2.id)) ''
$byS = @((Api GET "${lib}?uploadedBy=$sunilId&q=e2e&size=50" $asha).items)
Check 'filter by uploader' ((@($byS | Where-Object { $_.uploadedBy -ne $sunilId }).Count -eq 0) -and (@($byS | Where-Object { $_.id -in @($d1.id, $d2.id) }).Count -eq 2)) (J $byS.Count)
$tags = (Api GET "$lib/overview" $sunil).tags
Check 'tags in use are suggested' (@($tags) -contains 'guidelines') (J $tags)

# --- open, links, activity ---
$c = Get-Bytes "$lib/$($d1.id)/content" $priya
Check 'Priya opens the PDF' ($c.status -eq 200 -and $c.type -like 'application/pdf*' -and [Text.Encoding]::ASCII.GetString($c.bytes, 0, 5) -eq '%PDF-') "$($c.status)"
$l = Api POST "$lib/$($d1.id)/link" $sunil
Check 'a 5-minute download link is issued' ($l.url -like '*/api/v1/public/files/*' -and $l.expiresAt) (J $l)
$path = $l.url.Substring($l.url.IndexOf('/api/v1/'))
$pub = try { $r = Invoke-WebRequest "$api$path" -UseBasicParsing; [pscustomobject]@{ status = [int]$r.StatusCode; disp = $r.Headers['Content-Disposition'] } } catch { [pscustomobject]@{ status = [int]$_.Exception.Response.StatusCode } }
Check 'the link downloads without signing in, as an attachment' ($pub.status -eq 200 -and $pub.disp -like 'attachment*E2E Volunteer guidelines 2026.pdf*') (J $pub)
$bad = try { (Invoke-WebRequest ("$api$path" + 'x') -UseBasicParsing).StatusCode } catch { [int]$_.Exception.Response.StatusCode }
Check 'a tampered link is refused' ($bad -eq 403) "$bad"

# --- edit, delete, bin ---
$x = Api PUT "$lib/$($d1.id)" $priya @{ title = 'E2E hijack'; category = 'OTHER'; version = $d1.version }
Check 'only the uploader or an admin edits (403)' ($x.__status -eq 403 -and $x.__code -eq 'NOT_YOUR_DOCUMENT') (J $x)
$u = Api PUT "$lib/$($d1.id)" $sunil @{ title = 'E2E Volunteer guidelines 2026 (final)'; category = 'OTHER'; tags = @('e2e', 'guidelines'); visibility = 'TEAM'; version = $d1.version }
Check 'Sunil renames his document' ($u.title -eq 'E2E Volunteer guidelines 2026 (final)' -and $u.version -gt $d1.version) (J $u)
$x = Api PUT "$lib/$($d1.id)" $sunil @{ title = 'E2E stale'; category = 'OTHER'; version = $d1.version }
Check 'stale edit refused (409)' ($x.__status -eq 409) (J $x)
$del = Api DELETE "$lib/$($d1.id)" $sunil
Check 'delete moves it to the recycle bin for 30 days' ($del.status -eq 'DELETED' -and $del.purgeAfter -eq (Get-Date).AddDays(30).ToString('yyyy-MM-dd')) (J $del)
Check 'deleted documents leave the search' (-not ((Ids (Api GET "${lib}?q=e2e" $sunil)) -contains $d1.id)) ''
Check 'Asha sees it in the recycle bin' (@((Api GET "$lib/bin" $asha) | Where-Object { $_.id -eq $d1.id }).Count -eq 1) ''
$r = Api POST "$lib/$($d1.id)/restore" $sunil
Check 'Sunil restores it' ($r.status -eq 'ACTIVE') (J $r)
$fin = Multi $lib $asha 'meta' @{ title = 'E2E Bank statement Oct'; category = 'FINANCE'; documentDate = (Get-Date).ToString('yyyy-MM-dd') } 'file' @(, (Pdf 'bank')) 'application/pdf'
$fd = Api DELETE "$lib/$($fin.id)" $asha
Check 'finance records stay at least 8 years even when deleted' ($fd.purgeAfter -eq (Get-Date).AddYears(8).ToString('yyyy-MM-dd')) (J $fd)
$act = @((Api GET "$lib/$($d1.id)/activity" $asha) | ForEach-Object { $_.action })
Check 'activity log: uploaded, viewed, link, downloaded, updated, deleted, restored' (@('UPLOADED', 'VIEWED', 'LINK_CREATED', 'DOWNLOADED', 'UPDATED', 'DELETED', 'RESTORED' | Where-Object { $act -notcontains $_ }).Count -eq 0) (J $act)
$x = Api GET "$lib/$($d1.id)/activity" $sunil
Check 'only admins read the activity log (403)' ($x.__status -eq 403) (J $x)
$ov2 = Api GET "$lib/overview" $asha
Check 'storage used grows with uploads' ([long]$ov2.usedBytes -gt $before) "$before -> $($ov2.usedBytes)"

# --- meetings ---
$mb = "/api/v1/ngos/$NgoId/meetings"
$att = @(@{ userId = $ashaId; name = 'Asha Patil'; designation = 'President' }, @{ userId = $raviId; name = 'Ravi Kulkarni'; designation = 'Secretary' },
         @{ userId = $priyaId; name = 'Priya Deshmukh'; designation = 'Treasurer' }, @{ name = 'CA Mehta'; designation = 'Auditor (invitee)' })
$x = Api POST $mb $sunil @{ type = 'STAFF'; title = 'E2E not allowed'; startsAt = (Iso 1) }
Check 'staff cannot schedule meetings (403)' ($x.__status -eq 403) (J $x)
$agm = Api POST $mb $asha @{ type = 'AGM'; title = 'E2E Annual General Meeting 2026'; startsAt = (Iso 7); mode = 'IN_PERSON'; venue = 'Community hall, Wadi'; agenda = "1. Adopt accounts FY 2025-26`n2. Appoint auditor"; quorumRequired = 3; attendees = $att }
Check 'Asha schedules the AGM (restricted to invitees by default)' ($agm.meeting.status -eq 'SCHEDULED' -and $agm.meeting.visibility -eq 'RESTRICTED' -and @($agm.attendees).Count -eq 4) (J $agm.meeting)
$board = Api POST $mb $asha @{ type = 'BOARD'; title = 'E2E Board meeting October'; startsAt = (Iso 0 -2); mode = 'HYBRID'; venue = 'NGO office'; onlineLink = 'https://meet.example/abc'; agenda = '1. Campaign review'; quorumRequired = 2; attendees = $att }
$staffMeet = Api POST $mb $asha @{ type = 'STAFF'; title = 'E2E Staff meeting'; startsAt = (Iso 2); attendees = @() }
Check 'staff meetings are visible to the whole team' ($staffMeet.meeting.visibility -eq 'TEAM') (J $staffMeet.meeting)
Check 'Ravi is invited in the app' (@(WaitFor { Inbox $ravi 'MEETING_SCHEDULED' "/ngo/meetings/$($agm.meeting.id)" } 60).Count -ge 1) ''
$sl = @((Api GET "${mb}?when=all" $sunil) | ForEach-Object { $_.id })
Check 'Sunil (not invited) sees the staff meeting but not the board meeting' ($sl -contains $staffMeet.meeting.id -and -not ($sl -contains $board.meeting.id)) (J $sl)
$x = Api GET "$mb/$($board.meeting.id)" $sunil
Check 'opening a restricted meeting: not found' ($x.__status -eq 404) (J $x)
$x = Api POST "$mb/$($agm.meeting.id)/minutes" $asha @{ minutes = 'E2E too early'; version = $agm.meeting.version }
Check 'minutes cannot be written before the meeting' ($x.__code -eq 'MEETING_NOT_HELD') (J $x)
$bm = $board.meeting; $ids = @{}; foreach ($a in $board.attendees) { $ids[$a.name] = $a.id }
$attendance = @{}; $attendance[$ids['Asha Patil']] = $true; $attendance[$ids['Ravi Kulkarni']] = $true; $attendance[$ids['Priya Deshmukh']] = $false; $attendance[$ids['CA Mehta']] = $true
$mn = Api POST "$mb/$($bm.id)/minutes" $asha @{ minutes = "The board reviewed the School books campaign.`n`nThe treasurer reported spending against the budget."; chairperson = 'Asha Patil'; attendance = $attendance; version = $bm.version }
Check 'Asha records minutes and attendance (quorum 3 of 2)' ($mn.meeting.status -eq 'MINUTES_DRAFT' -and $mn.presentCount -eq 3 -and $mn.quorumMet) (J @{ s = $mn.meeting.status; p = $mn.presentCount })
$r1 = Api POST "$mb/$($bm.id)/resolutions" $asha @{ text = 'the second lot of 160 school bags be purchased from Sharma Stationers.'; proposedBy = 'Ravi Kulkarni'; secondedBy = 'Asha Patil'; votesFor = 3; votesAgainst = 0; result = 'PASSED' }
Check 'a resolution is added' (@($r1.resolutions).Count -eq 1 -and -not $r1.resolutions[0].number) (J $r1.resolutions)
$a1 = Api POST "$mb/$($bm.id)/actions" $asha @{ text = 'E2E Collect three quotations for school bags'; ownerUserId = $sunilId; dueDate = (Get-Date).AddDays(10).ToString('yyyy-MM-dd') }
Check 'an action item is given to Sunil' (@($a1.actions | Where-Object { $_.ownerUserId -eq $sunilId -and $_.ownerName -like 'Sunil*' }).Count -eq 1) (J $a1.actions)
Check 'Sunil is told about his action item' (@(WaitFor { Inbox $sunil 'MEETING_ACTION_ASSIGNED' "/ngo/meetings/$($bm.id)" } 60).Count -ge 1) ''
$cur = Api GET "$mb/$($bm.id)" $asha
$x = Api POST "$mb/$($bm.id)/approve" $asha @{ version = ($cur.meeting.version + 3) }
Check 'approving a stale version refused (409)' ($x.__status -eq 409) (J $x)
$ap = Api POST "$mb/$($bm.id)/approve" $asha @{ version = $cur.meeting.version }
Check 'Asha approves: minutes locked, resolution numbered RES/<FY>/nnn' ($ap.meeting.status -eq 'MINUTES_APPROVED' -and $ap.resolutions[0].number -match '^RES/\d{4}-\d{2}/\d{3}$' -and $ap.meeting.minutesDocumentId) (J $ap.meeting)
$md = Api GET "$lib/$($ap.meeting.minutesDocumentId)" $asha
Check 'the minutes PDF is filed in the vault (Meetings, linked)' ($md.category -eq 'MEETING' -and $md.source -eq 'MEETING_MINUTES' -and $md.linkId -eq $bm.id -and $md.fileType -eq 'PDF') (J $md)
$mpdf = Get-Bytes "$lib/$($md.id)/content" $ravi
Check 'Ravi (invited admin) opens the minutes PDF' ($mpdf.status -eq 200 -and [Text.Encoding]::ASCII.GetString($mpdf.bytes, 0, 5) -eq '%PDF-') "$($mpdf.status)"
[IO.File]::WriteAllBytes("$PSScriptRoot\..\.agent\e2e-minutes.pdf", $mpdf.bytes)
$x = Api POST "$mb/$($bm.id)/resolutions" $asha @{ text = 'E2E after approval'; result = 'PASSED' }
Check 'approved minutes are locked' ($x.__code -eq 'MINUTES_APPROVED') (J $x)
$mine = @((Api GET "/api/v1/ngos/$NgoId/action-items?mine=true&status=OPEN" $sunil) | ForEach-Object { $_ })
$act1 = @($mine | Where-Object { $_.action.text -like 'E2E Collect*' })[0]
Check 'Sunil sees his action item with the meeting title' ($null -ne $act1 -and $act1.meetingTitle -eq 'E2E Board meeting October') (J $mine.Count)
$x = Api PUT "/api/v1/ngos/$NgoId/action-items/$($act1.action.id)" $priya @{ done = $true }
Check 'others cannot tick off Sunil''s item (403)' ($x.__status -eq 403) (J $x)
$dn = Api PUT "/api/v1/ngos/$NgoId/action-items/$($act1.action.id)" $sunil @{ done = $true }
Check 'Sunil marks it done (allowed after approval)' ($dn.status -eq 'DONE' -and $dn.doneBy -eq $sunilId) (J $dn)
$sum = Api GET "$mb/summary" $asha
Check 'summary: this year''s AGM is scheduled, due by 30 September' ($sum.agmThisYear.id -eq $agm.meeting.id -and $sum.agmDueBy -like '*-09-30') (J $sum)
$cn = Api POST "$mb/$($staffMeet.meeting.id)/cancel" $asha @{ reason = 'E2E moved to next week' }
Check 'a scheduled meeting can be cancelled with a reason' ($cn.meeting.status -eq 'CANCELLED') (J $cn.meeting)

# --- cleanup ---
& $psql -w -h localhost -U postgres -d document -q -f "$PSScriptRoot\sql\cleanup-e2e-documents.sql" | Out-Null
$left = & $psql -w -h localhost -U postgres -d document -t -A -c "select count(*) from documents where title like 'E2E %' and status <> 'PURGED'"
Check 'E2E data cleaned up' ("$left".Trim() -eq '0') "$left"
""
"RESULT: $pass passed, $fail failed"
