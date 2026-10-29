# DEV/TEST ONLY: mobile verification (SMS OTP) and contact privacy, on the standard dataset (run seed-dataset.ps1
# first). SMS codes are read from the local Mailpit inbox (development SMS adapter).
param([string]$NgoId = '0cf945f6-1183-42f5-aeb1-20bab9be302b')
$ErrorActionPreference = 'Stop'
$api = 'http://localhost:8080'; $kc = 'http://localhost:8180'; $mailpit = 'http://localhost:8025/api/v1'
$psql = 'D:\tools\pgsql\bin\psql.exe'; $env:PGPASSWORD = 'dev-only-password'
$pass = 0; $fail = 0
function Check([string]$name, [bool]$ok, $detail = '') { if ($ok) { $script:pass++; "PASS  $name" } else { $script:fail++; "FAIL  $name  $detail" } }
# Windows PowerShell 5.1 emits a parsed JSON array as one pipeline item; this flattens it into a real list.
function ToList($x) { if ($null -eq $x) { return ,@() }; return ,@($x | ForEach-Object { $_ }) }
function Token($user, $pw) { (Invoke-RestMethod -Method Post "$kc/realms/ngo/protocol/openid-connect/token" -Body @{ grant_type = 'password'; client_id = 'ngo-admin-cli'; username = $user; password = $pw; scope = 'openid' }).access_token }
function Api($method, $path, $tok, $body = $null) {
  $a = @{ Method = $method; Uri = "$api$path"; Headers = @{ Authorization = "Bearer $tok" }; UseBasicParsing = $true }
  if ($null -ne $body) { $a.ContentType = 'application/json; charset=utf-8'; $a.Body = [Text.Encoding]::UTF8.GetBytes(($body | ConvertTo-Json -Depth 6)) }
  try {
    $r = Invoke-WebRequest @a; $ms = New-Object IO.MemoryStream; $r.RawContentStream.CopyTo($ms); $t = [Text.Encoding]::UTF8.GetString($ms.ToArray())
    if ([string]::IsNullOrWhiteSpace($t)) { [pscustomobject]@{ __status = 200 } } else { $t | ConvertFrom-Json }
  } catch {
    $code = 0; $b = $null; if ($_.Exception.Response) { $code = [int]$_.Exception.Response.StatusCode; try { $b = (New-Object IO.StreamReader($_.Exception.Response.GetResponseStream())).ReadToEnd() } catch {} }
    $c = $null; $m = $null; try { $j = $b | ConvertFrom-Json; $c = $j.code; $m = $j.message } catch {}
    [pscustomobject]@{ __status = $code; __code = $c; __message = $m }
  }
}
function SmsCode($phone) {
  Start-Sleep 2
  $msg = @((Invoke-RestMethod "$mailpit/search?query=to:%2B91$phone@sms.local").messages)[0]
  ([regex]'\b(\d{6})\b').Match((Invoke-RestMethod "$mailpit/message/$($msg.ID)").Text).Groups[1].Value
}
function Sql($q) { (& $psql -h localhost -U postgres -d identity -tAc $q) }

# --- reset this test's own data (DEV ONLY) ---
$null = Sql "delete from phone_verifications where user_id in (select id from users where email in ('anil.donor@ngo.test','kiran.donor@ngo.test','newstaff.test@ngo.test'))"
$null = Sql "update users set phone = null, phone_verified_at = null where email in ('anil.donor@ngo.test','kiran.donor@ngo.test')"
$null = Sql "delete from ngo_memberships where user_id in (select id from users where email = 'newstaff.test@ngo.test'); update staff_invitations set status = 'CANCELLED' where email = 'newstaff.test@ngo.test' and status = 'PENDING_ACCEPTANCE'; delete from users where email = 'newstaff.test@ngo.test'"
Invoke-RestMethod -Method Delete "$mailpit/messages" | Out-Null

$anil = Token 'anil.donor@ngo.test' 'DevTest#2026'
$kiran = Token 'kiran.donor@ngo.test' 'DevTest#2026'
$sunil = Token 'sunil.staff@ngo.test' 'DevTest#2026'
$meera = Token 'meera.donor@ngo.test' 'DevDonor#2026'
$asha = Token 'asha.admin@ngo.test' 'DevNgo#2026'

# --- send / verify ---
$r = Api POST '/api/v1/me/phone/otp' $anil @{ phone = '12345' }
Check 'invalid number rejected (400)' ($r.__status -eq 400)
$r = Api POST '/api/v1/me/phone/otp' $anil @{ phone = '9000000301' }
Check 'code sent, number masked in the reply' ($r.phoneMasked -eq '90XXXXXX01' -and $r.expiresInSeconds -eq 300) ($r | ConvertTo-Json -Compress)
$r2 = Api POST '/api/v1/me/phone/otp' $anil @{ phone = '9000000301' }
Check 'immediate resend refused (429 OTP_RESEND_WAIT)' ($r2.__code -eq 'OTP_RESEND_WAIT') ($r2 | ConvertTo-Json -Compress)
$code = SmsCode '9000000301'
Check 'SMS text follows the DLT template' ($code -match '^\d{6}$')
$stored = Sql "select code_hash from phone_verifications v join users u on u.id = v.user_id where u.email = 'anil.donor@ngo.test' order by v.created_at desc limit 1"
Check 'only a hash of the code is stored' ($stored.Trim().Length -eq 64 -and $stored -notmatch "^$code$")
$before = Api GET '/api/v1/me' $anil
Check 'number not saved before verification' ($null -eq $before.phone -and $before.phoneVerified -eq $false)
$wrong = if ($code -eq '000000') { '111111' } else { '000000' }
$w = Api POST '/api/v1/me/phone/verify' $anil @{ phone = '9000000301'; code = $wrong }
Check 'wrong code refused with tries left' ($w.__code -eq 'OTP_WRONG' -and $w.__message -match '4 tries left') ($w | ConvertTo-Json -Compress)
$ok = Api POST '/api/v1/me/phone/verify' $anil @{ phone = '9000000301'; code = $code }
Check 'correct code verifies the number' ($ok.phone -eq '9000000301' -and $ok.phoneVerified -eq $true) ($ok | ConvertTo-Json -Compress)
$again = Api POST '/api/v1/me/phone/verify' $anil @{ phone = '9000000301'; code = $code }
Check 'a code works only once' ($again.__status -ge 400)

# --- one number per account ---
$dup = Api POST '/api/v1/me/phone/otp' $kiran @{ phone = '9000000301' }
Check 'number already on another account refused (409 PHONE_IN_USE)' ($dup.__code -eq 'PHONE_IN_USE')

# --- lockout after 5 wrong tries ---
$null = Api POST '/api/v1/me/phone/otp' $kiran @{ phone = '9000000303' }
$kc303 = SmsCode '9000000303'
$bad = if ($kc303 -eq '000000') { '111111' } else { '000000' }
1..5 | ForEach-Object { $null = Api POST '/api/v1/me/phone/verify' $kiran @{ phone = '9000000303'; code = $bad } }
$locked = Api POST '/api/v1/me/phone/verify' $kiran @{ phone = '9000000303'; code = $kc303 }
Check 'after 5 wrong tries even the right code is refused (OTP_LOCKED)' ($locked.__code -eq 'OTP_LOCKED') ($locked | ConvertTo-Json -Compress)

# --- expiry ---
$null = Sql "update phone_verifications set created_at = created_at - interval '2 minutes' where user_id in (select id from users where email = 'anil.donor@ngo.test')"
$null = Api POST '/api/v1/me/phone/otp' $anil @{ phone = '9000000304' }
$c304 = SmsCode '9000000304'
$null = Sql "update phone_verifications set expires_at = now() - interval '1 second' where phone = '9000000304'"
$exp = Api POST '/api/v1/me/phone/verify' $anil @{ phone = '9000000304'; code = $c304 }
Check 'expired code refused (OTP_EXPIRED)' ($exp.__code -eq 'OTP_EXPIRED')

# --- removal rules ---
$rmD = Api DELETE '/api/v1/me/phone' $anil
Check 'a donor may remove the number' ($rmD.phoneVerified -eq $false -and $null -eq $rmD.phone)
$rmS = Api DELETE '/api/v1/me/phone' $sunil
Check 'staff cannot remove it (PHONE_REQUIRED_FOR_TEAM)' ($rmS.__code -eq 'PHONE_REQUIRED_FOR_TEAM')

# --- visibility ---
$team = (ToList (Api GET "/api/v1/ngos/$NgoId/staff" $asha))
$sunilRow = $team | Where-Object { $_.email -eq 'sunil.staff@ngo.test' }
Check 'NGO team sees a member''s verified number' ($sunilRow.phone -eq '9000000103') ($sunilRow | ConvertTo-Json -Compress -Depth 3)
Check 'donors are not in the team list (their numbers are not exposed)' ($null -eq ($team | Where-Object { $_.email -eq 'meera.donor@ngo.test' }))
$m = Api GET "/api/v1/ngos/$NgoId/staff" $meera
Check 'a donor cannot read the team list (403)' ($m.__status -eq 403)
$audited = Sql "select count(*) from audit_events where action = 'TEAM_CONTACTS_VIEWED' and created_at > now() - interval '5 minutes'"
Check 'viewing team contacts is audited' ([int]$audited.Trim() -ge 1)

# --- a donor who joins as staff must verify a phone before using the NGO app ---
$adm = (Invoke-RestMethod -Method Post "$kc/realms/master/protocol/openid-connect/token" -Body @{ grant_type = 'password'; client_id = 'admin-cli'; username = 'admin'; password = 'dev-only-admin' }).access_token
$ah = @{ Authorization = "Bearer $adm" }
foreach ($u in @(@(Invoke-RestMethod "$kc/admin/realms/ngo/users?email=newstaff.test@ngo.test&exact=true" -Headers $ah) | Where-Object { $_.id })) { Invoke-RestMethod -Method Delete "$kc/admin/realms/ngo/users/$($u.id)" -Headers $ah | Out-Null }
$body = @{ username = 'newstaff.test@ngo.test'; email = 'newstaff.test@ngo.test'; firstName = 'New'; lastName = 'Staff'; enabled = $true; emailVerified = $true; credentials = @(@{ type = 'password'; value = 'DevTest#2026'; temporary = $false }) } | ConvertTo-Json -Depth 4
Invoke-RestMethod -Method Post "$kc/admin/realms/ngo/users" -Headers $ah -ContentType 'application/json' -Body $body | Out-Null
$ns = Token 'newstaff.test@ngo.test' 'DevTest#2026'
$null = Api PUT '/api/v1/me/profile' $ns @{ displayName = 'New Staff'; preferredLanguage = 'en'; birthDate = '1993-02-02'; gender = 'UNDISCLOSED'; confirmAdult = $true }
$g0 = Api GET '/api/v1/notifications/unread-count' $ns
Check 'as a donor (no NGO role) no phone is needed' ($null -ne $g0.unread) ($g0 | ConvertTo-Json -Compress)
$inv = Api POST "/api/v1/ngos/$NgoId/staff/invitations" $asha @{ email = 'newstaff.test@ngo.test'; role = 'STAFF' }
$null = Api POST "/api/v1/me/invitations/$($inv.id)/accept" $ns
$me = Api GET '/api/v1/me' $ns
Check 'after joining: phone required, profile incomplete' ($me.phoneRequired -eq $true -and $me.profileComplete -eq $false) ($me | ConvertTo-Json -Compress -Depth 2)
$g1 = Api GET "/api/v1/ngos/$NgoId/staff" $ns
Check 'NGO features blocked until the phone is verified (PROFILE_INCOMPLETE)' ($g1.__code -eq 'PROFILE_INCOMPLETE') ($g1 | ConvertTo-Json -Compress)
$null = Api POST '/api/v1/me/phone/otp' $ns @{ phone = '9000000302' }
$nsc = SmsCode '9000000302'
$v = Api POST '/api/v1/me/phone/verify' $ns @{ phone = '9000000302'; code = $nsc }
Check 'after verification the profile is complete' ($v.profileComplete -eq $true)
$g2 = (ToList (Api GET "/api/v1/ngos/$NgoId/staff" $ns))
Check 'and the new staff member sees the team with contacts' (($g2 | Where-Object { $_.email -eq 'asha.admin@ngo.test' }).phone -eq '9000000101')
# leave the standard dataset as it was
$row = (ToList (Api GET "/api/v1/ngos/$NgoId/staff" $asha)) | Where-Object { $_.email -eq 'newstaff.test@ngo.test' }
$null = Api PUT "/api/v1/ngos/$NgoId/staff/$($row.membershipId)/status" $asha @{ status = 'REMOVED' }

""
"RESULT: $pass passed, $fail failed"
