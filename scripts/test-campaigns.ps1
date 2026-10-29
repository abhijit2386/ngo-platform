# DEV/TEST ONLY: end-to-end check of the campaign approval workflow through the gateway, using the standard
# dataset (scripts/seed-dataset.ps1): admins Asha + Ravi, staff Sunil (STAFF) + Priya (FINANCE), donors Meera + Kiran.
# Rule under test (owner, 2026-10-29): any team member creates a campaign; it goes live only after admin approval.
# Re-runnable: campaigns titled "E2E ..." are removed first.
param([string]$NgoId = '0cf945f6-1183-42f5-aeb1-20bab9be302b')
$ErrorActionPreference = 'Stop'
$api = 'http://localhost:8080'; $kc = 'http://localhost:8180/realms/ngo/protocol/openid-connect/token'
$mailpit = 'http://localhost:8025/api/v1'
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
    $args2 = @{ Method = $method; Uri = "$api$path"; Headers = $h; UseBasicParsing = $true }
    if ($null -ne $body) { $args2.ContentType = 'application/json; charset=utf-8'; $args2.Body = [Text.Encoding]::UTF8.GetBytes(($body | ConvertTo-Json -Depth 6)) }
    $resp = Invoke-WebRequest @args2
    $ms = New-Object IO.MemoryStream; $resp.RawContentStream.CopyTo($ms)
    $text = [Text.Encoding]::UTF8.GetString($ms.ToArray())
    if ([string]::IsNullOrWhiteSpace($text)) { $null } else { $text | ConvertFrom-Json }
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
function Inbox($tok, $template, $campaignId) {
  $r = Api GET '/api/v1/notifications?size=50' $tok
  @($r.items | Where-Object { $_.templateCode -eq $template -and $_.link -eq "/ngo/campaigns/$campaignId" })
}
function MailText($id) {
  $r = Invoke-WebRequest "$mailpit/message/$id" -UseBasicParsing
  $ms = New-Object IO.MemoryStream; $r.RawContentStream.CopyTo($ms)
  [Text.Encoding]::UTF8.GetString($ms.ToArray()) | ConvertFrom-Json
}
function Png([int]$w, [int]$h) {
  Add-Type -AssemblyName System.Drawing
  $bmp = New-Object Drawing.Bitmap $w, $h
  $g = [Drawing.Graphics]::FromImage($bmp); $g.Clear([Drawing.Color]::SeaGreen); $g.Dispose()
  $ms = New-Object IO.MemoryStream; $bmp.Save($ms, [Drawing.Imaging.ImageFormat]::Png); $bmp.Dispose()
  return $ms.ToArray()
}
function Upload($path, $tok, [byte[]]$bytes) {
  Add-Type -AssemblyName System.Net.Http
  $c = New-Object Net.Http.HttpClient
  $c.DefaultRequestHeaders.Authorization = New-Object Net.Http.Headers.AuthenticationHeaderValue('Bearer', $tok)
  $form = New-Object Net.Http.MultipartFormDataContent
  $part = New-Object Net.Http.ByteArrayContent(, $bytes)
  $part.Headers.ContentType = [Net.Http.Headers.MediaTypeHeaderValue]::Parse('image/png')
  $form.Add($part, 'file', 'cover.png')
  $r = $c.PostAsync("$api$path", $form).Result
  $body = $r.Content.ReadAsStringAsync().Result
  $c.Dispose()
  [pscustomobject]@{ status = [int]$r.StatusCode; body = $body }
}
function Status($path, $tok) {
  try {
    $h = @{}; if ($tok) { $h.Authorization = "Bearer $tok" }
    $r = Invoke-WebRequest "$api$path" -Headers $h -UseBasicParsing
    [pscustomobject]@{ status = [int]$r.StatusCode; type = $r.Headers['Content-Type']; bytes = $r.RawContentLength }
  } catch { [pscustomobject]@{ status = [int]$_.Exception.Response.StatusCode } }
}

# --- reset (DEV ONLY) ---
$env:PGPASSWORD = 'dev-only-password'
& 'D:\tools\pgsql\bin\psql.exe' -h localhost -U postgres -d campaign -q -c "delete from campaign_donations where campaign_id in (select id from campaigns where title like 'E2E %'); delete from campaigns where title like 'E2E %';" | Out-Null

$asha = Token 'asha.admin@ngo.test' 'DevNgo#2026'
$ravi = Token 'ravi.staff@ngo.test' 'DevStaff#2026'
$sunil = Token 'sunil.staff@ngo.test' 'DevTest#2026'
$priya = Token 'priya.admin@ngo.test' 'DevTest#2026'
$meera = Token 'meera.donor@ngo.test' 'DevDonor#2026'
Check 'tokens issued (2 admins, 2 staff, donor)' ($asha -and $ravi -and $sunil -and $priya -and $meera)
$ids = @{}
foreach ($p in @(@('asha', $asha), @('ravi', $ravi), @('sunil', $sunil), @('priya', $priya))) { $ids[$p[0]] = (Api GET '/api/v1/me' $p[1]).userId }
$base = "/api/v1/ngos/$NgoId/campaigns"
$today = (Get-Date).ToString('yyyy-MM-dd')
$end = (Get-Date).AddDays(45).ToString('yyyy-MM-dd')
$draft = @{ title = 'E2E School books for 200 children'; summary = 'Notebooks, textbooks and bags for children of Wadi village school.';
            story = 'Every June families struggle to buy books.'; cause = 'EDUCATION'; goalAmount = '50000'; startDate = $today; endDate = $end }

# --- permissions to create ---
$c = Api POST $base $sunil $draft
Check 'staff (Sunil) creates a draft' ($c.status -eq 'DRAFT' -and $c.createdBy -eq $ids.sunil) (J $c)
$id = $c.id
$x = Api POST $base $meera $draft
Check 'donor (Meera) cannot create (403)' ($x.__status -eq 403) (J $x)
$x = Api GET $base $meera
Check 'donor cannot list team campaigns (403)' ($x.__status -eq 403) (J $x)
$x = Api GET "/api/v1/ngos/$([guid]::NewGuid())/campaigns" $sunil
Check 'other NGO is off limits (403)' ($x.__status -eq 403) (J $x)

# --- validation ---
$bad = $draft.Clone(); $bad.goalAmount = '500'
$x = Api POST $base $sunil $bad; Check 'goal below Rs 1,000 rejected' ($x.__code -eq 'INVALID_GOAL') (J $x)
$bad = $draft.Clone(); $bad.endDate = (Get-Date).AddDays(-1).ToString('yyyy-MM-dd')
$x = Api POST $base $sunil $bad; Check 'end before start rejected' ($x.__code -eq 'INVALID_DATES') (J $x)
$bad = $draft.Clone(); $bad.Remove('endDate')
$x = Api POST $base $sunil $bad; Check 'end date required' ($x.__status -eq 400) (J $x)
$bad = $draft.Clone(); $bad.endDate = (Get-Date).AddDays(400).ToString('yyyy-MM-dd')
$x = Api POST $base $sunil $bad; Check 'longer than a year rejected' ($x.__code -eq 'TOO_LONG') (J $x)
$bad = $draft.Clone(); $bad.cause = 'POLITICS'
$x = Api POST $base $sunil $bad; Check 'unknown cause rejected' ($x.__code -eq 'INVALID_CAUSE') (J $x)

# --- draft is private ---
$pub = Api GET "/api/v1/public/campaigns?size=50" $null
Check 'draft not in public list' (-not (@($pub.items) | Where-Object { $_.id -eq $id }))
$x = Api GET "/api/v1/public/campaigns/$id" $null
Check 'draft not publicly readable (404)' ($x.__status -eq 404) (J $x)

# --- cover image ---
$up = Upload "$base/$id/image" $sunil (Png 1600 1200)
Check 'Sunil uploads a cover (re-encoded)' ($up.status -eq 200 -and ($up.body | ConvertFrom-Json).hasImage) $up.body
$img = Status "$base/$id/image?size=small" $sunil
Check 'team sees draft cover as JPEG' ($img.status -eq 200 -and $img.type -like 'image/jpeg*') (J $img)
$img = Status "/api/v1/public/campaigns/$id/image" $null
Check 'draft cover is not public (404)' ($img.status -eq 404) (J $img)
$up = Upload "$base/$id/image" $sunil (Png 200 100)
Check 'tiny picture rejected' ($up.status -eq 400 -and $up.body -match 'IMAGE_TOO_SMALL') $up.body

# --- staff cannot approve ---
$c = Api GET "$base/$id" $sunil
$x = Api POST "$base/$id/approve" $sunil @{ version = $c.version }
Check 'staff cannot approve own draft (403)' ($x.__status -eq 403) (J $x)

# --- submit -> admins told ---
$c = Api POST "$base/$id/submit" $sunil
Check 'Sunil sends for approval' ($c.status -eq 'PENDING_APPROVAL' -and $c.submittedBy -eq $ids.sunil) (J $c)
$n = @(WaitFor { Inbox $asha 'CAMPAIGN_SUBMITTED' $id } 60)
Check 'Asha (admin) notified in-app' ($n.Count -ge 1)
if ($n) { "      in-app: $($n[0].title) / $($n[0].body)" }
$n = @(WaitFor { Inbox $ravi 'CAMPAIGN_SUBMITTED' $id } 60)
Check 'Ravi (admin) notified in-app' ($n.Count -ge 1)
Check 'Ravi''s notification is in Hindi' ($n.Count -ge 1 -and $n[0].title -match 'अभियान') (J $n)
$n = @(Inbox $sunil 'CAMPAIGN_SUBMITTED' $id)
Check 'submitter is not notified about own request' ($n.Count -eq 0)
$x = Api POST "$base/$id/submit" $sunil
Check 'cannot submit twice' ($x.__code -eq 'INVALID_CAMPAIGN_TRANSITION') (J $x)

# --- other staff may edit while pending; admin must approve the latest version ---
$seen = Api GET "$base/$id" $asha
$e = @{} + $draft; $e.summary = 'Notebooks, textbooks and school bags for 200 children of Wadi village.'; $e.version = $seen.version
$c = Api PUT "$base/$id" $priya $e
Check 'Priya (Finance) edits the pending campaign' ($c.status -eq 'PENDING_APPROVAL' -and $c.version -gt $seen.version) (J $c)
$x = Api POST "$base/$id/approve" $asha @{ version = $seen.version }
Check 'approving a stale version is refused (409)' ($x.__code -eq 'STALE_VERSION') (J $x)

# --- request changes ---
$x = Api POST "$base/$id/reject" $asha @{ reason = 'no' }
Check 'reason required to request changes' ($x.__status -eq 400) (J $x)
$x = Api POST "$base/$id/reject" $sunil @{ reason = 'Looks fine to me' }
Check 'staff cannot request changes (403)' ($x.__status -eq 403) (J $x)
$c = Api POST "$base/$id/reject" $asha @{ reason = 'Please add how many books each child gets.' }
Check 'Asha requests changes -> back to draft with reason' ($c.status -eq 'DRAFT' -and $c.rejectionReason -like 'Please add*') (J $c)
$n = @(WaitFor { Inbox $sunil 'CAMPAIGN_REJECTED' $id } 60)
Check 'Sunil told what to change' ($n.Count -ge 1 -and $n[0].body -like '*how many books*') (J $n)

# --- fix + resubmit -> Ravi approves ---
$cur = Api GET "$base/$id" $sunil
$e = @{} + $draft; $e.story = 'Each child gets 6 notebooks, a full textbook set and a bag.'; $e.version = $cur.version
$c = Api PUT "$base/$id" $sunil $e
$c = Api POST "$base/$id/submit" $sunil
Check 'resubmitted; old reason cleared' ($c.status -eq 'PENDING_APPROVAL' -and -not $c.rejectionReason) (J $c)
$c = Api POST "$base/$id/approve" $ravi @{ version = $c.version }
Check 'Ravi (secondary admin) approves -> live' ($c.status -eq 'PUBLISHED' -and $c.approvedBy -eq $ids.ravi) (J $c)
$n = @(WaitFor { Inbox $sunil 'CAMPAIGN_APPROVED' $id } 60)
Check 'Sunil told the campaign is live (in-app)' ($n.Count -ge 1)
$mail = @(WaitFor { @((Invoke-RestMethod "$mailpit/search?query=to:sunil.staff@ngo.test").messages | Where-Object { $_.Subject -like '*is live*E2E School*' }) } 60)
Check 'Sunil gets the "live" e-mail' ($mail.Count -ge 1)

# --- public view ---
$pub = Api GET "/api/v1/public/campaigns?size=50" $null
$p = @($pub.items | Where-Object { $_.id -eq $id })
Check 'live campaign listed publicly with NGO name' ($p.Count -eq 1 -and $p[0].ngoName -eq 'Asha Seva' -and $p[0].phase -eq 'LIVE') (J $p)
$d = Api GET "/api/v1/public/campaigns/$id" $null
Check 'public detail hides team data' ($d.title -and -not ($d.PSObject.Properties.Name -contains 'createdBy') -and -not ($d.PSObject.Properties.Name -contains 'approvedBy')) (J $d)
$pub = Api GET "/api/v1/public/campaigns?q=wadi&cause=EDUCATION" $null
Check 'public search by text + cause finds it' (@($pub.items | Where-Object { $_.id -eq $id }).Count -eq 1) (J $pub)
$img = Status "/api/v1/public/campaigns/$id/image?size=small" $null
Check 'live cover is public' ($img.status -eq 200) (J $img)

# --- live edits: admins only ---
$cur = Api GET "$base/$id" $sunil
$e = @{} + $draft; $e.version = $cur.version
$x = Api PUT "$base/$id" $sunil $e
Check 'staff cannot edit a live campaign (403)' ($x.__code -eq 'ADMIN_ONLY_LIVE_EDIT') (J $x)

# --- test donations ---
$s = Api GET "$base/summary" $sunil
Check 'summary: counts + test donations on (dev)' ($s.testDonations -eq $true -and $s.counts.PUBLISHED -ge 1) (J $s)
$c = Api POST "$base/$id/test-donations" $sunil @{ amount = '2500'; donorLabel = 'Test donor A' }
$c = Api POST "$base/$id/test-donations" $priya @{ amount = '1000.50' }
Check 'test donations add up (Rs 3,500.50, 2 gifts)' ($c.raisedAmount -eq 3500.5 -and $c.donationCount -eq 2) (J $c)
$x = Api POST "$base/$id/test-donations" $sunil @{ amount = '5' }
Check 'amount below Rs 10 rejected' ($x.__code -eq 'INVALID_AMOUNT') (J $x)
$x = Api POST "$base/$id/test-donations" $meera @{ amount = '100' }
Check 'donor cannot record test donations (403)' ($x.__status -eq 403) (J $x)
$list = Api GET "$base/$id/donations" $asha
Check 'donation list shows both, marked test' (@($list).Count -eq 2 -and (@($list) | Where-Object { $_.test }).Count -eq 2) (J $list)
$cur = Api GET "$base/$id" $asha
$e = @{} + $draft; $e.goalAmount = '3000'; $e.version = $cur.version
$x = Api PUT "$base/$id" $asha $e
Check 'goal cannot drop below amount raised' ($x.__code -eq 'GOAL_BELOW_RAISED') (J $x)
$e.goalAmount = '60000'
$c = Api PUT "$base/$id" $asha $e
Check 'admin raises the goal of a live campaign' ($c.goalAmount -eq 60000 -and $c.status -eq 'PUBLISHED') (J $c)

# --- close + archive ---
$x = Api POST "$base/$id/close" $sunil
Check 'staff cannot close (403)' ($x.__status -eq 403) (J $x)
$x = Api POST "$base/$id/archive" $asha
Check 'live campaign must be closed before archiving' ($x.__code -eq 'CLOSE_FIRST') (J $x)
$c = Api POST "$base/$id/close" $asha
Check 'Asha closes it' ($c.status -eq 'CLOSED') (J $c)
$x = Api POST "$base/$id/test-donations" $sunil @{ amount = '100' }
Check 'closed campaign takes no donations' ($x.__code -eq 'CAMPAIGN_NOT_LIVE') (J $x)
$d = Api GET "/api/v1/public/campaigns/$id" $null
Check 'closed campaign shows as ended publicly' ($d.phase -eq 'ENDED') (J $d)
$x = Api POST "$base/$id/archive" $sunil
Check 'staff cannot archive a closed campaign (403)' ($x.__status -eq 403) (J $x)
$c = Api POST "$base/$id/archive" $asha
Check 'Asha archives it' ($c.status -eq 'ARCHIVED') (J $c)

# --- admin publishes own draft; no self-notification ---
$own = @{} + $draft; $own.title = 'E2E Admin direct campaign'
$c = Api POST $base $asha $own
$c = Api POST "$base/$($c.id)/approve" $asha @{ version = $c.version }
Check 'admin publishes own draft directly' ($c.status -eq 'PUBLISHED') (J $c)
Start-Sleep 8
Check 'no "approved" notification to herself' (@(Inbox $asha 'CAMPAIGN_APPROVED' $c.id).Count -eq 0)
$null = Api POST "$base/$($c.id)/close" $asha

# --- discard a draft; withdraw ---
$tmp = @{} + $draft; $tmp.title = 'E2E Discard me'
$c = Api POST $base $sunil $tmp
$c2 = Api POST "$base/$($c.id)/submit" $sunil
$x = Api POST "$base/$($c.id)/archive" $sunil
Check 'pending must be withdrawn before discarding' ($x.__code -eq 'WITHDRAW_FIRST') (J $x)
$c2 = Api POST "$base/$($c.id)/withdraw" $sunil
Check 'withdraw -> draft' ($c2.status -eq 'DRAFT') (J $c2)
$c2 = Api POST "$base/$($c.id)/archive" $sunil
Check 'staff discards own draft' ($c2.status -eq 'ARCHIVED') (J $c2)
$all = Api GET "${base}?size=100" $sunil
Check 'archived campaigns hidden from the default list' ($null -ne $all.items -and -not (@($all.items) | Where-Object { $_.id -eq $c.id })) (J $all)

# Leave the sample campaigns (seed-campaigns.ps1) uncluttered for UI checks.
& 'D:\tools\pgsql\bin\psql.exe' -h localhost -U postgres -d campaign -q -c "delete from campaign_donations where campaign_id in (select id from campaigns where title like 'E2E %'); delete from campaigns where title like 'E2E %';" | Out-Null

""
"campaigns e2e: $pass passed, $fail failed"
if ($fail -gt 0) { exit 1 }
