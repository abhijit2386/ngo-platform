# DEV/TEST ONLY: end-to-end check of notification-service through the gateway with fictional test accounts.
# Flow: Priya signs in (identity record) -> Asha invites Priya (FINANCE) -> e-mail lands in Mailpit + Priya's inbox
# -> Priya accepts -> Asha gets "New team member" -> preferences rules -> admin templates/preview
# -> retry path: Mailpit stopped, invite Kiran, delivery FAILED with backoff, Mailpit back, delivery SENT.
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
  $h = @{ Authorization = "Bearer $tok" }
  try {
    # Invoke-WebRequest + explicit UTF-8 decoding: Windows PowerShell 5.1 would otherwise read JSON as Latin-1.
    $args2 = @{ Method = $method; Uri = "$api$path"; Headers = $h; UseBasicParsing = $true }
    if ($null -ne $body) { $args2.ContentType = 'application/json; charset=utf-8'; $args2.Body = [Text.Encoding]::UTF8.GetBytes(($body | ConvertTo-Json -Depth 6)) }
    $resp = Invoke-WebRequest @args2
    $ms = New-Object IO.MemoryStream; $resp.RawContentStream.CopyTo($ms)
    $text = [Text.Encoding]::UTF8.GetString($ms.ToArray())
    if ([string]::IsNullOrWhiteSpace($text)) { $null } else { $text | ConvertFrom-Json }
  } catch { [pscustomobject]@{ __status = [int]$_.Exception.Response.StatusCode; __body = $_.ErrorDetails.Message } }
}
function MailMessage($id) {
  # UTF-8 decoding done explicitly (Windows PowerShell 5.1 would read Devanagari as Latin-1).
  $r = Invoke-WebRequest "$mailpit/message/$id" -UseBasicParsing
  $ms = New-Object IO.MemoryStream; $r.RawContentStream.CopyTo($ms)
  [Text.Encoding]::UTF8.GetString($ms.ToArray()) | ConvertFrom-Json
}
function MailTo($addr) {
  $r = Invoke-RestMethod "$mailpit/search?query=to:$addr"
  return $r.messages
}
function WaitFor([scriptblock]$cond, [int]$secs = 60) {
  $end = (Get-Date).AddSeconds($secs)
  while ((Get-Date) -lt $end) { $v = & $cond; if ($v) { return $v }; Start-Sleep 3 }
  return $null
}

# Re-runnable: reset the test people's memberships/invitations in the local identity database (DEV ONLY).
$env:PGPASSWORD = 'dev-only-password'
& 'D:\tools\pgsql\bin\psql.exe' -h localhost -U postgres -d identity -q -c "delete from ngo_memberships where ngo_id = '$NgoId' and user_id in (select id from users where email in ('priya.admin@ngo.test','outsider.test@ngo.test')); update staff_invitations set status = 'CANCELLED' where ngo_id = '$NgoId' and email in ('priya.admin@ngo.test','outsider.test@ngo.test') and status = 'PENDING_ACCEPTANCE';" | Out-Null
Invoke-RestMethod -Method Delete "$mailpit/messages" | Out-Null

$asha = Token 'asha.admin@ngo.test' 'DevNgo#2026'
$priya = Token 'priya.admin@ngo.test' 'DevTest#2026'
$admin = Token 'platform.admin@ngo.test' 'DevAdmin#2026'
Check 'tokens issued' ($asha -and $priya -and $admin)
# Requires the standard dataset (scripts/seed-dataset.ps1): completed profiles, verified team phones.
$null = Api GET '/api/v1/me' $priya
$null = Api POST '/api/v1/notifications/read-all' $priya   # earlier runs leave unread items   # creates Priya's identity record so the invitation also reaches her in-app inbox

# --- invitation e-mail + in-app ---
$inv = Api POST "/api/v1/ngos/$NgoId/staff/invitations" $asha @{ email = 'priya.admin@ngo.test'; role = 'FINANCE' }
Check 'Asha invites Priya as Finance' ($null -ne $inv.id) ($inv | ConvertTo-Json -Compress)
# Priya's language is Marathi in the standard dataset: the invitation must arrive in Marathi.
$mail = WaitFor { MailTo 'priya.admin@ngo.test' | Where-Object { $_.Subject -like "*Asha Seva*" } } 90
Check 'invitation e-mail delivered to Mailpit' ($null -ne $mail)
if ($mail) {
  $full = MailMessage (@($mail)[0].ID)
  "      subject: $($full.Subject)"
  Check 'e-mail is in Priya''s language (Marathi) with NGO and role' ($full.Text -match 'Asha Seva' -and $full.Text -match 'वित्त' -and $full.Text -match 'आमंत्रित') $full.Text
  Check 'e-mail sender is Seva Setu' ($full.From.Name -eq 'Seva Setu')
}
$pInbox = WaitFor { $r = Api GET '/api/v1/notifications' $priya; if ($r.items.Count -gt 0) { $r } } 30
Check "Priya's in-app inbox has the invitation" ($pInbox.items[0].templateCode -eq 'STAFF_INVITED') ($pInbox | ConvertTo-Json -Compress -Depth 4)
$cnt = Api GET '/api/v1/notifications/unread-count' $priya
Check 'unread count is 1' ($cnt.unread -eq 1) ($cnt | ConvertTo-Json -Compress)

# --- accept -> admins told ---
$accept = Api POST "/api/v1/me/invitations/$($inv.id)/accept" $priya
Check 'Priya accepts' ($accept.role -eq 'FINANCE') ($accept | ConvertTo-Json -Compress)
$aInbox = WaitFor { $r = Api GET '/api/v1/notifications' $asha; $x = $r.items | Where-Object { $_.templateCode -eq 'STAFF_JOINED' }; if ($x) { $x } } 60
Check 'Asha gets "New team member" in-app' ($null -ne $aInbox)
if ($aInbox) { "      in-app: $(@($aInbox)[0].title) / $(@($aInbox)[0].body)" }
$joinMail = WaitFor { MailTo 'asha.admin@ngo.test' | Where-Object { $_.Subject -like '*joined*' } } 60
Check 'Asha gets "joined" e-mail' ($null -ne $joinMail)

# --- read state ---
$n = @($aInbox)[0]
$r = Api POST "/api/v1/notifications/$($n.id)/read" $asha
Check 'mark one as read' ($r.read -eq $true)
$null = Api POST '/api/v1/notifications/read-all' $asha
Check 'read-all leaves nothing unread' ((Api GET '/api/v1/notifications/unread-count' $asha).unread -eq 0)
$other = Api POST "/api/v1/notifications/$($n.id)/read" $priya
Check "Priya cannot read Asha's notification (404)" ($other.__status -eq 404) ($other | ConvertTo-Json -Compress)

# --- preferences ---
$prefs = Api GET '/api/v1/notifications/preferences' $asha
Check 'six categories, ACCOUNT mandatory' ($prefs.Count -eq 6 -and ($prefs | Where-Object { $_.category -eq 'ACCOUNT' }).mandatory) ($prefs | ConvertTo-Json -Compress)
$bad = Api PUT '/api/v1/notifications/preferences' $asha @{ preferences = @(@{ category = 'ACCOUNT'; email = $false; inApp = $true; push = $true }) }
Check 'mandatory category cannot be switched off (422)' ($bad.__status -eq 422) ($bad | ConvertTo-Json -Compress)
$ok = Api PUT '/api/v1/notifications/preferences' $asha @{ preferences = @(@{ category = 'BILLING'; email = $false; inApp = $true; push = $true }) }
Check 'billing e-mail switched off' ((@($ok) | Where-Object { $_.category -eq 'BILLING' }).email -eq $false)
$null = Api PUT '/api/v1/notifications/preferences' $asha @{ preferences = @(@{ category = 'BILLING'; email = $true; inApp = $true; push = $true }) }
$unk = Api PUT '/api/v1/notifications/preferences' $asha @{ preferences = @(@{ category = 'NOPE'; email = $true; inApp = $true; push = $true }) }
Check 'unknown category rejected (400)' ($unk.__status -eq 400)
$hist = Api GET '/api/v1/notifications/history' $asha
Check 'communication history lists the sent e-mail' (@($hist.items | Where-Object { $_.status -eq 'SENT' }).Count -ge 1) ($hist | ConvertTo-Json -Compress -Depth 4)

# --- admin templates ---
$tpl = Api GET '/api/v1/admin/notification-templates' $admin
Check '60 seeded templates (10 codes x 2 channels x 3 languages)' ($tpl.Count -eq 60) "count=$($tpl.Count)"
$denied = Api GET '/api/v1/admin/notification-templates' $asha
Check 'NGO admin cannot manage platform templates (403)' ($denied.__status -eq 403)
$t1 = $tpl | Where-Object { $_.code -eq 'STAFF_INVITED' -and $_.channel -eq 'EMAIL' -and $_.language -eq 'hi' }
$pv = Api POST "/api/v1/admin/notification-templates/$($t1.id)/preview" $admin @{ sample = @{ ngoName = 'Asha Seva'; role = 'वित्त' } }
Check 'Hindi preview renders sample values' ($pv.subject -match 'Asha Seva' -and $pv.body -match 'वित्त') ($pv | ConvertTo-Json -Compress)
$badEdit = Api PUT "/api/v1/admin/notification-templates/$($t1.id)" $admin @{ subject = $t1.subject; body = 'Hi {{password}}'; active = $true; version = $t1.version }
Check 'edit with unknown placeholder rejected (422)' ($badEdit.__status -eq 422)
$stale = Api PUT "/api/v1/admin/notification-templates/$($t1.id)" $admin @{ subject = $t1.subject; body = $t1.body; active = $true; version = 99 }
Check 'stale edit rejected (409)' ($stale.__status -eq 409)

# --- retry path: SMTP down -> FAILED with backoff -> SMTP back -> SENT ---
Get-Process mailpit -ErrorAction SilentlyContinue | Stop-Process -Force
Start-Sleep 2
$inv2 = Api POST "/api/v1/ngos/$NgoId/staff/invitations" $asha @{ email = 'outsider.test@ngo.test'; role = 'VOLUNTEER' }
Check 'Asha invites an outsider while e-mail is down' ($null -ne $inv2.id) ($inv2 | ConvertTo-Json -Compress)
$failed = WaitFor { $r = Api GET '/api/v1/notifications/admin/deliveries?status=FAILED' $admin; $x = $r.items | Where-Object { $_.recipient -eq 'outsider.test@ngo.test' }; if ($x) { $x } } 90
Check 'delivery is FAILED with a scheduled retry' ($null -ne $failed -and $failed.nextAttemptAt) ($failed | ConvertTo-Json -Compress)
if ($failed) { "      error: $($failed.lastError)  next attempt: $($failed.nextAttemptAt)" }
Start-Process -FilePath 'D:\tools\mailpit\mailpit.exe' -ArgumentList '--listen', '127.0.0.1:8025', '--smtp', '127.0.0.1:1025' `
  -RedirectStandardOutput 'D:\ngo-platform\logs\mailpit.log' -RedirectStandardError 'D:\ngo-platform\logs\mailpit.err.log' -WindowStyle Hidden
$retried = WaitFor { try { MailTo 'outsider.test@ngo.test' | Where-Object { $_.Subject -like '*invited*' } } catch { $null } } 150
Check 'automatic retry delivers once e-mail is back' ($null -ne $retried)

""
"RESULT: $pass passed, $fail failed"
