# DEV/TEST ONLY - standard dataset (owner rule 2026-10-29): every feature is developed and tested with at least
# 2 NGO admins, 2 staff and 3 donors. Idempotent: run any time to restore the known state. Fictional people only.
# Uses the local Keycloak master admin and the app's own APIs (so business rules are exercised, not bypassed).
param([string]$NgoId = '0cf945f6-1183-42f5-aeb1-20bab9be302b')   # "Asha Seva" (registered and approved in UI tests)
$ErrorActionPreference = 'Stop'
$api = 'http://localhost:8080'; $kc = 'http://localhost:8180'; $mailpit = 'http://localhost:8025/api/v1'

$people = @(
  @{ email = 'asha.admin@ngo.test';  pw = 'DevNgo#2026';   first = 'Asha';  last = 'Patil';     dob = '1985-04-12'; gender = 'FEMALE';      lang = 'en'; phone = '9000000101'; role = 'PRIMARY_ADMIN' }
  @{ email = 'ravi.staff@ngo.test';  pw = 'DevStaff#2026'; first = 'Ravi';  last = 'Kulkarni';  dob = '1980-11-02'; gender = 'MALE';        lang = 'hi'; phone = '9000000102'; role = 'SECONDARY_ADMIN' }
  @{ email = 'sunil.staff@ngo.test'; pw = 'DevTest#2026';  first = 'Sunil'; last = 'More';      dob = '1992-10-29'; gender = 'UNDISCLOSED'; lang = 'en'; phone = '9000000103'; role = 'STAFF' }
  @{ email = 'priya.admin@ngo.test'; pw = 'DevTest#2026';  first = 'Priya'; last = 'Deshmukh';  dob = '1988-09-03'; gender = 'FEMALE';      lang = 'mr'; phone = '9000000104'; role = 'FINANCE' }
  @{ email = 'meera.donor@ngo.test'; pw = 'DevDonor#2026'; first = 'Meera'; last = 'Iyer';      dob = '1990-01-20'; gender = 'FEMALE';      lang = 'en'; phone = '9000000201'; role = $null }
  @{ email = 'kiran.donor@ngo.test'; pw = 'DevTest#2026';  first = 'Kiran'; last = 'Joshi';     dob = '1975-07-07'; gender = 'UNDISCLOSED'; lang = 'mr'; phone = $null;        role = $null }
  @{ email = 'anil.donor@ngo.test';  pw = 'DevTest#2026';  first = 'Anil';  last = 'Shah';      dob = '1995-03-15'; gender = 'MALE';        lang = 'hi'; phone = $null;        role = $null }
)

function Api($method, $path, $tok, $body = $null) {
  $a = @{ Method = $method; Uri = "$api$path"; Headers = @{ Authorization = "Bearer $tok" }; UseBasicParsing = $true }
  if ($null -ne $body) { $a.ContentType = 'application/json; charset=utf-8'; $a.Body = [Text.Encoding]::UTF8.GetBytes(($body | ConvertTo-Json -Depth 6)) }
  try {
    $r = Invoke-WebRequest @a
    $ms = New-Object IO.MemoryStream; $r.RawContentStream.CopyTo($ms); $t = [Text.Encoding]::UTF8.GetString($ms.ToArray())
    if ([string]::IsNullOrWhiteSpace($t)) { $null } else { $t | ConvertFrom-Json }
  } catch {
    $b = $null; if ($_.Exception.Response) { try { $b = (New-Object IO.StreamReader($_.Exception.Response.GetResponseStream())).ReadToEnd() } catch {} }
    throw "$method $path failed ($($_.Exception.Message)): $b"
  }
}
# Windows PowerShell 5.1 emits a parsed JSON array as one pipeline item; this flattens it into a real list.
function ToList($x) { if ($null -eq $x) { return ,@() }; return ,@($x | ForEach-Object { $_ }) }
function Token($user, $pw) {
  (Invoke-RestMethod -Method Post "$kc/realms/ngo/protocol/openid-connect/token" -Body @{ grant_type = 'password'; client_id = 'ngo-admin-cli'; username = $user; password = $pw; scope = 'openid' }).access_token
}

# --- 1. Keycloak accounts ---
$adm = (Invoke-RestMethod -Method Post "$kc/realms/master/protocol/openid-connect/token" -Body @{
  grant_type = 'password'; client_id = 'admin-cli'; username = 'admin'; password = 'dev-only-admin' }).access_token
$ah = @{ Authorization = "Bearer $adm" }
$offline = Invoke-RestMethod "$kc/admin/realms/ngo/roles/offline_access" -Headers $ah
foreach ($p in $people) {
  $u = @(@(Invoke-RestMethod "$kc/admin/realms/ngo/users?email=$($p.email)&exact=true" -Headers $ah) | Where-Object { $_.id })
  if ($u.Count -eq 0) {
    $body = @{ username = $p.email; email = $p.email; firstName = $p.first; lastName = $p.last; enabled = $true; emailVerified = $true } | ConvertTo-Json
    Invoke-RestMethod -Method Post "$kc/admin/realms/ngo/users" -Headers $ah -ContentType 'application/json' -Body $body | Out-Null
    $u = @(@(Invoke-RestMethod "$kc/admin/realms/ngo/users?email=$($p.email)&exact=true" -Headers $ah) | Where-Object { $_.id })
    "keycloak: created $($p.email)"
  }
  $id = $u[0].id
  $p.id = $id
  $upd = @{ firstName = $p.first; lastName = $p.last; enabled = $true; emailVerified = $true } | ConvertTo-Json
  Invoke-RestMethod -Method Put "$kc/admin/realms/ngo/users/$id" -Headers $ah -ContentType 'application/json' -Body $upd | Out-Null
  $cred = @{ type = 'password'; value = $p.pw; temporary = $false } | ConvertTo-Json
  Invoke-RestMethod -Method Put "$kc/admin/realms/ngo/users/$id/reset-password" -Headers $ah -ContentType 'application/json' -Body $cred | Out-Null
  Invoke-RestMethod -Method Post "$kc/admin/realms/ngo/users/$id/role-mappings/realm" -Headers $ah -ContentType 'application/json' -Body (ConvertTo-Json -InputObject @($offline) -Depth 4) | Out-Null
}

# --- 2. Profiles (adults only) and verified phones (SMS codes read from the local Mailpit inbox) ---
foreach ($p in $people) {
  $p.tok = Token $p.email $p.pw
  $me = Api PUT '/api/v1/me/profile' $p.tok @{ displayName = "$($p.first) $($p.last)"; preferredLanguage = $p.lang; birthDate = $p.dob; gender = $p.gender; confirmAdult = $true }
  if ($p.phone -and $me.phone -ne $p.phone) {
    $null = Api POST '/api/v1/me/phone/otp' $p.tok @{ phone = $p.phone }
    Start-Sleep 2
    $msg = @((Invoke-RestMethod "$mailpit/search?query=to:%2B91$($p.phone)@sms.local").messages)[0]
    $text = (Invoke-RestMethod "$mailpit/message/$($msg.ID)").Text
    $code = ([regex]'\b(\d{6})\b').Match($text).Groups[1].Value
    $me = Api POST '/api/v1/me/phone/verify' $p.tok @{ phone = $p.phone; code = $code }
    "phone verified: $($p.email)"
  }
}

# --- 3. NGO team: invite + accept missing members; donors stay outside the team ---
$asha = @($people | Where-Object { $_.role -eq 'PRIMARY_ADMIN' })[0]   # @() needed: [0] on a single hashtable is a key lookup
$members = (ToList (Api GET "/api/v1/ngos/$NgoId/staff" $asha.tok))
$pending = (ToList (Api GET "/api/v1/ngos/$NgoId/staff/invitations" $asha.tok))
foreach ($p in ($people | Where-Object { $_.role -and $_.role -ne 'PRIMARY_ADMIN' })) {
  $m = $members | Where-Object { $_.email -eq $p.email -and $_.status -eq 'ACTIVE' }
  if ($m) { if ($m.role -ne $p.role) { "WARNING: $($p.email) is $($m.role), expected $($p.role)" }; continue }
  $inv = $pending | Where-Object { $_.email -eq $p.email -and $_.role -eq $p.role }
  if (-not $inv) { $inv = Api POST "/api/v1/ngos/$NgoId/staff/invitations" $asha.tok @{ email = $p.email; role = $p.role } }
  $null = Api POST "/api/v1/me/invitations/$($inv.id)/accept" $p.tok
  "joined: $($p.email) as $($p.role)"
}
foreach ($p in ($people | Where-Object { -not $_.role })) {
  foreach ($inv in ($pending | Where-Object { $_.email -eq $p.email })) {
    $null = Api DELETE "/api/v1/ngos/$NgoId/staff/invitations/$($inv.id)" $asha.tok
    "cancelled stray invitation for donor $($p.email)"
  }
  if ($members | Where-Object { $_.email -eq $p.email -and $_.status -eq 'ACTIVE' }) { "WARNING: donor $($p.email) is an NGO member" }
}

# --- 4. Summary ---
""
"Standard dataset (NGO $NgoId):"
$final = (ToList (Api GET "/api/v1/ngos/$NgoId/staff" $asha.tok))
foreach ($p in $people) {
  $me = Api GET '/api/v1/me' $p.tok
  $m = $final | Where-Object { $_.email -eq $p.email -and $_.status -eq 'ACTIVE' }
  "{0,-24} {1,-16} profile={2,-5} phone={3,-10} lang={4}" -f $p.email, ($(if ($m) { $m.role } else { 'DONOR' })), $me.profileComplete, $(if ($me.phoneVerified) { 'verified' } else { '-' }), $me.preferredLanguage
}
$admins = @($final | Where-Object { $_.status -eq 'ACTIVE' -and ($_.role -eq 'PRIMARY_ADMIN' -or $_.role -eq 'SECONDARY_ADMIN') }).Count
$staff = @($final | Where-Object { $_.status -eq 'ACTIVE' -and $_.role -ne 'PRIMARY_ADMIN' -and $_.role -ne 'SECONDARY_ADMIN' }).Count
"admins=$admins staff=$staff donors=3  -> " + $(if ($admins -ge 2 -and $staff -ge 2) { 'OK' } else { 'INCOMPLETE' })
