# DEV/TEST ONLY: end-to-end CSR funding proposals (campaign-project-service, Enterprise) with the standard dataset:
#   admin Asha, staff Sunil, campaign "School books for 200 children" with its budget and approved expenses, and the
#   shared compliance pack from seed-csr.ps1.
# Plan gate, draft from the campaign (pre-filled), edits and checks, draft PDF, sending (frozen PDF in the vault),
# notes and follow-ups, outcome (funded / declined / withdrawn), copy, the funded proposal leading to a utilisation
# certificate, pipeline summary, read-only after a downgrade.
# Re-runnable: E2E proposals (and certificates, vault entries) are removed at the start and end.
param([string]$NgoId = '0cf945f6-1183-42f5-aeb1-20bab9be302b')
$ErrorActionPreference = 'Stop'
$api = 'http://localhost:8080'; $kc = 'http://localhost:8180/realms/ngo/protocol/openid-connect/token'
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
function Get-Bytes($path, $tok) {
  try {
    $h = @{}; if ($tok) { $h.Authorization = "Bearer $tok" }
    $r = Invoke-WebRequest "$api$path" -Headers $h -UseBasicParsing
    $ms = New-Object IO.MemoryStream; $r.RawContentStream.CopyTo($ms)
    [pscustomobject]@{ status = [int]$r.StatusCode; type = $r.Headers['Content-Type']; bytes = $ms.ToArray(); disp = $r.Headers['Content-Disposition'] }
  } catch { [pscustomobject]@{ status = [int]$_.Exception.Response.StatusCode; bytes = @() } }
}
function Sha([byte[]]$b) { -join ([Security.Cryptography.SHA256]::Create().ComputeHash($b) | ForEach-Object { $_.ToString('x2') }) }
function Plan($p) { $null = Api PUT "/api/v1/ngos/$NgoId/subscription/trial-plan" $asha @{ plan = $p }; Start-Sleep 32 } # entitlement cache: 30 s
function Cleanup {
  $sql = "BEGIN; ALTER TABLE utilisation_certificates DISABLE TRIGGER trg_uc_guard; " +
    "DELETE FROM utilisation_certificates WHERE funder_name LIKE 'E2E %' OR proposal_id IN (SELECT id FROM csr_proposals WHERE funder_name LIKE 'E2E %'); " +
    "ALTER TABLE utilisation_certificates ENABLE TRIGGER trg_uc_guard; " +
    "ALTER TABLE proposal_events DISABLE TRIGGER trg_proposal_events_guard; ALTER TABLE csr_proposals DISABLE TRIGGER trg_proposal_guard; " +
    "DELETE FROM proposal_events WHERE proposal_id IN (SELECT id FROM csr_proposals WHERE funder_name LIKE 'E2E %'); " +
    "DELETE FROM csr_proposals WHERE funder_name LIKE 'E2E %'; " +
    "ALTER TABLE proposal_events ENABLE TRIGGER trg_proposal_events_guard; ALTER TABLE csr_proposals ENABLE TRIGGER trg_proposal_guard; COMMIT;"
  & $psql -w -h localhost -U postgres -d campaign -q -c $sql 2>$null | Out-Null
  $sql = "BEGIN; ALTER TABLE document_activity DISABLE TRIGGER trg_document_activity_immutable; " +
    "DELETE FROM document_activity WHERE document_id IN (SELECT id FROM documents WHERE source IN ('CSR_PROPOSAL','UTILISATION_CERT') AND title LIKE '%E2E %'); " +
    "DELETE FROM documents WHERE source IN ('CSR_PROPOSAL','UTILISATION_CERT') AND title LIKE '%E2E %'; " +
    "ALTER TABLE document_activity ENABLE TRIGGER trg_document_activity_immutable; COMMIT;"
  & $psql -w -h localhost -U postgres -d document -q -c $sql 2>$null | Out-Null
}
function Day($n) { (Get-Date).AddDays($n).ToString('yyyy-MM-dd') }

$asha = Token 'asha.admin@ngo.test' 'DevNgo#2026'
$sunil = Token 'sunil.staff@ngo.test' 'DevTest#2026'
$base = "/api/v1/ngos/$NgoId/csr-proposals"
Cleanup
$camp = @((Api GET "/api/v1/ngos/$NgoId/campaigns?size=50" $asha).items | Where-Object { $_.title -eq 'School books for 200 children' })[0]
$new = @{ campaignId = $camp.id; funderName = 'E2E Sahyadri Industries CSR Foundation'; askAmount = '250000' }

# --- plan gate -------------------------------------------------------------------------------------------------------
Plan 'PROFESSIONAL'
$x = Api POST $base $asha $new
Check 'Professional: proposals refused (402, Enterprise)' ($x.__status -eq 402 -and $x.__code -eq 'ENTITLEMENT_REQUIRED:CSR_PROPOSALS') (J $x)
$l = Api GET $base $asha
Check 'list says the feature is off and offers the Schedule VII areas' ($l.enabled -eq $false -and @($l.scheduleVii).Count -eq 10) (J $l.enabled)
Plan 'ENTERPRISE'
$x = Api POST $base $sunil $new
Check 'staff cannot write proposals (403)' ($x.__status -eq 403) (J $x)
$x = Api GET $base $sunil
Check 'staff cannot read proposals (403)' ($x.__status -eq 403) (J $x)

# --- draft from the campaign ----------------------------------------------------------------------------------------
$d = Api POST $base $asha $new
Check 'Asha starts a proposal: PR/<FY>/nnn, draft, title from the campaign' ($d.number -match '^PR/\d{4}-\d{2}/\d{3}$' -and $d.status -eq 'DRAFT' -and $d.title -eq $camp.title) (J @{ n = $d.number; s = $d.status; t = $d.title })
Check 'pre-filled: need from the campaign story, Schedule VII area, 12 months, contact' ($d.need.Length -gt 20 -and $d.scheduleVii -and $d.durationMonths -eq 12) (J @{ s = $d.scheduleVii; m = $d.durationMonths; c = $d.contactName })
Check 'progress from the campaign: budget lines and approved expenses' (@($d.progress.budget).Count -ge 1 -and [int]$d.progress.approvedExpenses -ge 1 -and [decimal]$d.progress.spent -gt 0) (J $d.progress)
Check 'draft lists what is missing before it can be sent' (@($d.missing).Count -ge 2) (J $d.missing)
$x = Api POST "$base/$($d.id)/send" $asha @{ sentOn = (Day 0); version = $d.version }
Check 'incomplete proposal cannot be sent' ($x.__status -eq 422 -and $x.__code -eq 'PROPOSAL_INCOMPLETE') (J $x)

# a shared pack from seed-csr
$ov = Api GET "/api/v1/ngos/$NgoId/csr/overview" $asha
$pk = @($ov.packs | Where-Object { $_.status -eq 'SHARED' })[0]
$pd = if ($pk) { Api GET "/api/v1/ngos/$NgoId/csr/packs/$($pk.id)" $asha } else { $null }
$edit = @{ title = 'Text books for 200 children in Kothrud'; funderName = 'E2E Sahyadri Industries CSR Foundation'; funderContact = 'Ms. Kavita Rao'
  funderEmail = 'csr@sahyadri.example'; scheduleVii = 'EDUCATION'; askAmount = '250000'; durationMonths = 12; beneficiaries = 200; location = 'Kothrud, Pune'
  need = $d.need; objectives = 'Every child in classes 1 to 8 of two municipal schools gets a full set of text books and notebooks.'
  activities = 'Assess needs with head teachers; buy from local stationers; distribute in school; track use each term.'
  outcomes = '200 children with books from the first day; attendance up by 10% within a year.'; monitoring = ''
  contactName = 'Asha Patil, Chairperson'; contactDetails = 'asha.admin@ngo.test'
  packId = $pk.id; packTitle = $pk.title; packUrl = $pd.link; packExpiresAt = $pd.expiresAt; version = $d.version }
$bad = $edit.Clone(); $bad.packUrl = 'https://evil.example/api/v1/public/csr/AAAAAAAAAAAAAAAAAAAA'
$x = Api PUT "$base/$($d.id)" $asha $bad
Check 'foreign links cannot be printed as the compliance pack' ($x.__status -eq 400 -and $x.__code -eq 'INVALID_PACK_LINK') (J $x)
$bad = $edit.Clone(); $bad.scheduleVii = 'PM_FUND'
$x = Api PUT "$base/$($d.id)" $asha $bad
Check 'unknown Schedule VII area refused' ($x.__status -eq 400) (J $x)
$u = Api PUT "$base/$($d.id)" $asha $edit
Check 'edited: funder contact, area, beneficiaries, compliance pack link' ($u.version -gt $d.version -and $u.scheduleVii -eq 'EDUCATION' -and $u.beneficiaries -eq 200 -and ($null -eq $pk -or $u.packUrl -eq $pd.link) -and @($u.missing).Count -eq 0) (J @{ v = $u.version; p = $u.packUrl; m = $u.missing })
$x = Api PUT "$base/$($d.id)" $asha $edit
Check 'stale edit refused (409)' ($x.__status -eq 409) (J $x)
$p = Get-Bytes "$base/$($d.id)/pdf" $asha
Check 'draft PDF opens' ($p.status -eq 200 -and $p.bytes.Length -gt 3000 -and [Text.Encoding]::ASCII.GetString($p.bytes, 0, 5) -eq '%PDF-' -and $p.disp -match 'draft') "$($p.status) $($p.bytes.Length)"

# --- send ------------------------------------------------------------------------------------------------------------
$x = Api POST "$base/$($d.id)/send" $asha @{ sentOn = (Day 1); version = $u.version }
Check 'sending date in the future refused' ($x.__status -eq 400) (J $x)
$s = Api POST "$base/$($d.id)/send" $asha @{ sentOn = (Day 0); followUpOn = (Day 7); version = $u.version }
Check 'sent: status, follow-up date, fingerprint of the PDF as sent' ($s.status -eq 'SENT' -and $s.followUpOn -eq (Day 7) -and $s.pdfSha256.Length -eq 64) (J @{ s = $s.status; f = $s.followUpOn })
$p = Get-Bytes "$base/$($d.id)/pdf" $asha
Check 'the stored PDF is the one sent (fingerprint matches)' ((Sha $p.bytes) -eq $s.pdfSha256 -and $p.disp -notmatch 'draft') (Sha $p.bytes)
$x = Api PUT "$base/$($d.id)" $asha ($edit + @{}) 
Check 'a sent proposal cannot be edited' ($x.__status -eq 422 -or $x.__status -eq 409) (J $x)
$v = WaitFor { @((Api GET "/api/v1/ngos/$NgoId/library?q=CSR%20proposal&size=20" $asha).items | Where-Object { $_.title -match [regex]::Escape($s.number) -and $_.category -eq 'CSR' }) } 60
Check 'filed in the vault: category CSR, linked to the campaign' ($v -and @($v)[0].linkId -eq $camp.id) (J $v)

# --- notes and follow-ups --------------------------------------------------------------------------------------------
$n = Api POST "$base/$($d.id)/notes" $asha @{ note = 'Called Ms. Rao; the CSR committee meets on the 20th.'; followUpOn = (Day 0) }
Check 'note with a new follow-up date; shows as due today' ($n.followUpOn -eq (Day 0) -and $n.followUpDue -eq $true -and @($n.events | Where-Object { $_.kind -eq 'NOTE' }).Count -eq 1) (J @{ f = $n.followUpOn; d = $n.followUpDue })
$x = Api POST "$base/$($d.id)/notes" $asha @{ note = 'x'; followUpOn = (Day -1) }
Check 'follow-up in the past refused' ($x.__status -eq 400) (J $x)
$l = Api GET $base $asha
Check 'pipeline: open proposal counted, money asked, follow-up due' ([int]$l.summary.open -ge 1 -and [decimal]$l.summary.askedOpen -ge 250000 -and [int]$l.summary.followUpsDue -ge 1) (J $l.summary)

# --- outcome ---------------------------------------------------------------------------------------------------------
$x = Api POST "$base/$($d.id)/close" $asha @{ outcome = 'FUNDED'; amount = '200000'; on = (Day -400) }
Check 'grant date before sending refused' ($x.__status -eq 400) (J $x)
$f = Api POST "$base/$($d.id)/close" $asha @{ outcome = 'FUNDED'; amount = '200000'; on = (Day 0); reference = 'SCF/2026/14' }
Check 'funded: amount, date, the funder reference; follow-up cleared' ($f.status -eq 'FUNDED' -and [decimal]$f.fundedAmount -eq 200000 -and $f.funderReference -eq 'SCF/2026/14' -and $null -eq $f.followUpOn) (J @{ s = $f.status; a = $f.fundedAmount })
$x = Api POST "$base/$($d.id)/close" $asha @{ outcome = 'WITHDRAWN'; reason = 'E2E' }
Check 'a closed proposal stays closed' ($x.__status -eq 422) (J $x)
$x = Api POST "$base/$($d.id)/notes" $asha @{ note = 'late note' }
Check 'no notes on a closed proposal' ($x.__status -eq 422) (J $x)

# --- utilisation certificate for the grant ---------------------------------------------------------------------------
$uc = Api POST "/api/v1/ngos/$NgoId/ucs" $asha @{ campaignId = $camp.id; funderName = 'E2E Sahyadri Industries CSR Foundation'; funderReference = 'SCF/2026/14'
  grantAmount = '200000'; grantReceivedOn = (Day 0); periodFrom = [string]$camp.startDate; periodTo = (Day 0); proposalId = $d.id }
$g = Api GET "$base/$($d.id)" $asha
Check 'utilisation certificate made for the funded proposal and listed on it' ($uc.proposalId -eq $d.id -and @($g.certificates | Where-Object { $_.id -eq $uc.id }).Count -eq 1) (J @{ uc = $uc.proposalId; c = $g.certificates })

# --- copy, decline, withdraw -----------------------------------------------------------------------------------------
$c = Api POST "$base/$($d.id)/copy" $asha
Check 'copy: a new draft with the same text and a new number' ($c.status -eq 'DRAFT' -and $c.number -ne $d.number -and $c.objectives -eq $edit.objectives -and @($c.events | Where-Object { $_.kind -eq 'COPIED' }).Count -eq 1) (J @{ n = $c.number; s = $c.status })
$x = Api POST "/api/v1/ngos/$NgoId/ucs" $asha @{ campaignId = $camp.id; funderName = 'E2E X'; grantAmount = '1000'; grantReceivedOn = (Day 0); periodFrom = [string]$camp.startDate; periodTo = (Day 0); proposalId = $c.id }
Check 'a certificate cannot name a proposal that was not funded' ($x.__status -eq 422 -and $x.__code -eq 'PROPOSAL_NOT_FUNDED') (J $x)
$x = Api POST "$base/$($c.id)/close" $asha @{ outcome = 'DECLINED'; reason = 'E2E' }
Check 'a draft cannot be declined (send it first)' ($x.__status -eq 422 -and $x.__code -eq 'NOT_SENT') (J $x)
$c2 = Api PUT "$base/$($c.id)" $asha ($edit.Clone() | ForEach-Object { $_.version = $c.version; $_ })
$s2 = Api POST "$base/$($c.id)/send" $asha @{ sentOn = (Day 0); version = $c2.version }
$k = Api POST "$base/$($c.id)/close" $asha @{ outcome = 'DECLINED'; reason = 'E2E budget for this year already allocated' }
Check 'declined with the reason' ($k.status -eq 'DECLINED' -and $k.closeReason -match 'already allocated') (J @{ s = $k.status })
$w = Api POST $base $asha $new
$wk = Api POST "$base/$($w.id)/close" $asha @{ outcome = 'WITHDRAWN'; reason = 'E2E duplicate' }
Check 'a draft can be withdrawn' ($wk.status -eq 'WITHDRAWN') (J $wk.status)
$l = Api GET $base $asha
Check 'pipeline: funded total and success rate' ([decimal]$l.summary.fundedTotal -ge 200000 -and $null -ne $l.summary.successPercent) (J $l.summary)

# --- after a downgrade -----------------------------------------------------------------------------------------------
$o = Api POST $base $asha $new
$os = Api PUT "$base/$($o.id)" $asha ($edit.Clone() | ForEach-Object { $_.version = $o.version; $_ })
$os = Api POST "$base/$($o.id)/send" $asha @{ sentOn = (Day 0); version = $os.version }
Plan 'PROFESSIONAL'
$x = Api POST $base $asha $new
Check 'after a downgrade no new proposals' ($x.__status -eq 402) (J $x)
$g = Api GET "$base/$($o.id)" $asha
$k = Api POST "$base/$($o.id)/close" $asha @{ outcome = 'DECLINED'; reason = 'E2E end of test' }
Check 'but sent ones stay readable and their outcome can be recorded' ($g.status -eq 'SENT' -and $k.status -eq 'DECLINED') "$($g.status) $($k.status)"

Cleanup
"RESULT: $pass passed, $fail failed"
