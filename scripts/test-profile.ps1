# DEV/TEST ONLY: end-to-end check of profile completion and the adults-only rule with fictional local accounts.
# Creates throw-away Keycloak users (adult/teen/eighteen @ngo.test) through the local master admin, then checks:
# gate before completion, validation, completion, locked birth date, avatar/photo rules, under-18 refusal and removal,
# 18th birthday today, platform staff exemption.
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http, System.Drawing
$api = 'http://localhost:8080'; $kc = 'http://localhost:8180'
$pass = 0; $fail = 0
function Check([string]$name, [bool]$ok, $detail = '') {
  if ($ok) { $script:pass++; "PASS  $name" } else { $script:fail++; "FAIL  $name  $detail" }
}
function Token($user, $pw) {
  (Invoke-RestMethod -Method Post "$kc/realms/ngo/protocol/openid-connect/token" -Body @{ grant_type = 'password'; client_id = 'ngo-admin-cli'; username = $user; password = $pw; scope = 'openid' }).access_token
}
function Api($method, $path, $tok, $body = $null) {
  $args2 = @{ Method = $method; Uri = "$api$path"; Headers = @{ Authorization = "Bearer $tok" }; UseBasicParsing = $true }
  if ($null -ne $body) { $args2.ContentType = 'application/json; charset=utf-8'; $args2.Body = [Text.Encoding]::UTF8.GetBytes(($body | ConvertTo-Json -Depth 6)) }
  try {
    $resp = Invoke-WebRequest @args2
    $ms = New-Object IO.MemoryStream; $resp.RawContentStream.CopyTo($ms)
    $text = [Text.Encoding]::UTF8.GetString($ms.ToArray())
    if ([string]::IsNullOrWhiteSpace($text)) { [pscustomobject]@{ __status = [int]$resp.StatusCode } } else { $text | ConvertFrom-Json }
  } catch {
    $code = 0; if ($_.Exception.Response) { $code = [int]$_.Exception.Response.StatusCode }
    # Windows PowerShell 5.1 does not fill ErrorDetails for Invoke-WebRequest: read the error body from the response.
    $b = $_.ErrorDetails.Message
    if ([string]::IsNullOrEmpty($b) -and $_.Exception.Response) {
      try { $sr = New-Object IO.StreamReader($_.Exception.Response.GetResponseStream(), [Text.Encoding]::UTF8); $b = $sr.ReadToEnd() } catch {}
    }
    $c = $null; try { $c = ($b | ConvertFrom-Json).code } catch {}
    [pscustomobject]@{ __status = $code; __code = $c; __body = $b }
  }
}
function Bytes($path, $tok) {
  $h = New-Object System.Net.Http.HttpClient
  $h.DefaultRequestHeaders.Authorization = New-Object System.Net.Http.Headers.AuthenticationHeaderValue('Bearer', $tok)
  $r = $h.GetAsync("$api$path").Result
  [pscustomobject]@{ status = [int]$r.StatusCode; bytes = $r.Content.ReadAsByteArrayAsync().Result }
}
function Upload($tok, [byte[]]$data, $name) {
  $h = New-Object System.Net.Http.HttpClient
  $h.DefaultRequestHeaders.Authorization = New-Object System.Net.Http.Headers.AuthenticationHeaderValue('Bearer', $tok)
  $form = New-Object System.Net.Http.MultipartFormDataContent
  $form.Add((New-Object System.Net.Http.ByteArrayContent(,$data)), 'file', $name)
  $r = $h.PostAsync("$api/api/v1/me/photo", $form).Result
  $text = $r.Content.ReadAsStringAsync().Result
  $o = $null; try { $o = $text | ConvertFrom-Json } catch {}
  [pscustomobject]@{ status = [int]$r.StatusCode; body = $o }
}
function Png([int]$w, [int]$h) {
  $bmp = New-Object System.Drawing.Bitmap($w, $h)
  $g = [System.Drawing.Graphics]::FromImage($bmp); $g.Clear([System.Drawing.Color]::SeaGreen); $g.Dispose()
  $ms = New-Object IO.MemoryStream; $bmp.Save($ms, [System.Drawing.Imaging.ImageFormat]::Png); $bmp.Dispose()
  , $ms.ToArray()
}

# --- throw-away users (master admin, local only) ---
$adm = (Invoke-RestMethod -Method Post "$kc/realms/master/protocol/openid-connect/token" -Body @{
  grant_type = 'password'; client_id = 'admin-cli'; username = 'admin'; password = 'dev-only-admin' }).access_token
$ah = @{ Authorization = "Bearer $adm" }
$offline = Invoke-RestMethod "$kc/admin/realms/ngo/roles/offline_access" -Headers $ah
function NewUser($email, $first) {
  # Windows PowerShell 5.1 emits an empty JSON array as one item: filter on id.
  foreach ($u in @(@(Invoke-RestMethod "$kc/admin/realms/ngo/users?email=$email&exact=true" -Headers $ah) | Where-Object { $_.id })) {
    Invoke-RestMethod -Method Delete "$kc/admin/realms/ngo/users/$($u.id)" -Headers $ah | Out-Null
  }
  $body = @{ username = $email; email = $email; firstName = $first; lastName = 'Test'; enabled = $true; emailVerified = $true
    credentials = @(@{ type = 'password'; value = 'DevTest#2026'; temporary = $false }) } | ConvertTo-Json -Depth 4
  Invoke-RestMethod -Method Post "$kc/admin/realms/ngo/users" -Headers $ah -ContentType 'application/json' -Body $body | Out-Null
  $id = @(@(Invoke-RestMethod "$kc/admin/realms/ngo/users?email=$email&exact=true" -Headers $ah) | Where-Object { $_.id })[0].id
  Invoke-RestMethod -Method Post "$kc/admin/realms/ngo/users/$id/role-mappings/realm" -Headers $ah -ContentType 'application/json' -Body (ConvertTo-Json -InputObject @($offline) -Depth 4) | Out-Null
  $id
}
# Re-runnable: drop the previous runs' local identity records for these throw-away addresses (DEV ONLY).
$env:PGPASSWORD = 'dev-only-password'
& 'D:\tools\pgsql\bin\psql.exe' -h localhost -U postgres -d identity -q -c "delete from users where email in ('adult.test@ngo.test','teen.test@ngo.test','eighteen.test@ngo.test')" | Out-Null
$adultId = NewUser 'adult.test@ngo.test' 'Adult'
$teenId = NewUser 'teen.test@ngo.test' 'Teen'
$eighteenId = NewUser 'eighteen.test@ngo.test' 'Eighteen'
$adult = Token 'adult.test@ngo.test' 'DevTest#2026'
$teen = Token 'teen.test@ngo.test' 'DevTest#2026'
$eighteen = Token 'eighteen.test@ngo.test' 'DevTest#2026'
$admin = Token 'platform.admin@ngo.test' 'DevAdmin#2026'

# --- gate before completion ---
$me = Api GET '/api/v1/me' $adult
Check 'new user: /me works, profileComplete=false' ($me.profileComplete -eq $false) ($me | ConvertTo-Json -Compress)
$g1 = Api GET '/api/v1/notifications/unread-count' $adult
Check 'gate blocks notification-service (403 PROFILE_INCOMPLETE)' ($g1.__code -eq 'PROFILE_INCOMPLETE') ($g1 | ConvertTo-Json -Compress)
$g2 = Api GET '/api/v1/me/invitations' $adult
Check 'gate blocks identity invitations (403 PROFILE_INCOMPLETE)' ($g2.__code -eq 'PROFILE_INCOMPLETE')
$pa = Api GET '/api/v1/admin/verifications' $admin
Check 'platform staff are exempt from the gate' ($null -ne $pa.items) ($pa | ConvertTo-Json -Compress)

# --- validation ---
$base = @{ displayName = 'Adult Test'; phone = '9876543210'; preferredLanguage = 'hi'; birthDate = '1990-05-15'; gender = 'UNDISCLOSED'; confirmAdult = $true }
$v1 = Api PUT '/api/v1/me/profile' $adult ($base.Clone() | ForEach-Object { $_.confirmAdult = $false; $_ })
Check 'adult confirmation required (400)' ($v1.__code -eq 'ADULT_CONFIRMATION_REQUIRED') ($v1 | ConvertTo-Json -Compress)
$v2 = Api PUT '/api/v1/me/profile' $adult ($base.Clone() | ForEach-Object { $_.birthDate = (Get-Date).AddDays(3).ToString('yyyy-MM-dd'); $_ })
Check 'future birth date rejected (400)' ($v2.__code -eq 'INVALID_BIRTH_DATE')
$v3 = Api PUT '/api/v1/me/profile' $adult ($base.Clone() | ForEach-Object { $_.gender = 'OTHER'; $_ })
Check 'unknown gender rejected (400)' ($v3.__status -eq 400)
$v4 = Api PUT '/api/v1/me/profile' $adult ($base.Clone() | ForEach-Object { $_.phone = '12345'; $_ })
Check 'invalid mobile rejected (400)' ($v4.__status -eq 400)

# --- completion ---
$ok = Api PUT '/api/v1/me/profile' $adult $base
Check 'adult completes profile' ($ok.profileComplete -eq $true -and $ok.gender -eq 'UNDISCLOSED' -and $ok.preferredLanguage -eq 'hi') ($ok | ConvertTo-Json -Compress)
$g4 = Api GET '/api/v1/notifications/unread-count' $adult
Check 'gate opens immediately in other services' ($null -ne $g4.unread) ($g4 | ConvertTo-Json -Compress)
$lock = Api PUT '/api/v1/me/profile' $adult ($base.Clone() | ForEach-Object { $_.birthDate = '1991-01-01'; $_ })
Check 'birth date cannot be changed (409 BIRTH_DATE_LOCKED)' ($lock.__code -eq 'BIRTH_DATE_LOCKED')
$same = Api PUT '/api/v1/me/profile' $adult ($base.Clone() | ForEach-Object { $_.displayName = 'Adult Renamed'; $_.gender = 'FEMALE'; $_ })
Check 'profile edits with the same birth date work' ($same.displayName -eq 'Adult Renamed' -and $same.gender -eq 'FEMALE')

# --- pictures ---
$p1 = Api PUT '/api/v1/me/avatar' $adult @{ preset = 'a07' }
Check 'built-in avatar a07' ($p1.avatar.kind -eq 'PRESET' -and $p1.avatar.preset -eq 'a07') ($p1 | ConvertTo-Json -Compress -Depth 4)
$p2 = Api PUT '/api/v1/me/avatar' $adult @{ preset = 'a99' }
Check 'unknown avatar rejected (400)' ($p2.__status -eq 400)
$up = Upload $adult (Png 900 600) 'me.png'
Check 'photo upload (PNG 900x600)' ($up.status -eq 200 -and $up.body.avatar.kind -eq 'PHOTO' -and $up.body.avatar.photoVersion -ge 1) "status=$($up.status)"
$img = Bytes '/api/v1/me/photo?size=512' $adult
$bmp = [System.Drawing.Image]::FromStream((New-Object IO.MemoryStream(,$img.bytes)))
Check 'stored photo is a 512x512 JPEG' ($img.status -eq 200 -and $img.bytes[0] -eq 0xFF -and $img.bytes[1] -eq 0xD8 -and $bmp.Width -eq 512 -and $bmp.Height -eq 512) "status=$($img.status) $($bmp.Width)x$($bmp.Height)"
$small = Bytes '/api/v1/me/photo?size=128' $adult
$bmp2 = [System.Drawing.Image]::FromStream((New-Object IO.MemoryStream(,$small.bytes)))
Check 'thumbnail is 128x128' ($bmp2.Width -eq 128)
$txt = Upload $adult ([Text.Encoding]::UTF8.GetBytes('not an image at all, just text')) 'x.jpg'
Check 'non-image upload rejected (400)' ($txt.status -eq 400) "status=$($txt.status)"
$asha = Token 'asha.admin@ngo.test' 'DevNgo#2026'
$null = Api PUT '/api/v1/me/profile' $asha @{ displayName = 'Asha Patil'; phone = ''; preferredLanguage = 'en'; birthDate = '1985-04-12'; gender = 'FEMALE'; confirmAdult = $true }
$other = Bytes "/api/v1/users/$adultId/photo" $asha
Check "a stranger cannot see the photo (404)" ($other.status -eq 404) "status=$($other.status)"
$team = Api GET '/api/v1/ngos/0cf945f6-1183-42f5-aeb1-20bab9be302b/staff' $asha
Check 'team list carries avatar data' ($null -ne @($team)[0].avatar) (@($team)[0] | ConvertTo-Json -Compress -Depth 4)
$rm = Api DELETE '/api/v1/me/photo' $adult
Check 'remove picture -> initials' ($rm.avatar.kind -eq 'NONE')
$gone = Bytes '/api/v1/me/photo' $adult
Check 'removed photo is gone (404)' ($gone.status -eq 404)

# --- under 18 ---
$teenDob = (Get-Date).AddYears(-15).ToString('yyyy-MM-dd')
$t1 = Api PUT '/api/v1/me/profile' $teen @{ displayName = 'Teen Test'; phone = ''; preferredLanguage = 'en'; birthDate = $teenDob; gender = 'UNDISCLOSED'; confirmAdult = $true }
Check 'under-18 refused (403 UNDERAGE)' ($t1.__code -eq 'UNDERAGE') ($t1 | ConvertTo-Json -Compress)
$kcGone = @(@(Invoke-RestMethod "$kc/admin/realms/ngo/users?email=teen.test@ngo.test&exact=true" -Headers $ah) | Where-Object { $_.id }).Count -eq 0
Check 'under-18 Keycloak account deleted' $kcGone
$relogin = $null; try { $relogin = Token 'teen.test@ngo.test' 'DevTest#2026' } catch {}
Check 'under-18 cannot sign in again' ($null -eq $relogin)
$env:PGPASSWORD = 'dev-only-password'
$rows = & 'D:\tools\pgsql\bin\psql.exe' -h localhost -U postgres -d identity -tAc "select count(*) from users where email = 'teen.test@ngo.test'"
Check 'no personal data kept for the under-18 user' ($rows.Trim() -eq '0') "rows=$rows"
$auditRow = & 'D:\tools\pgsql\bin\psql.exe' -h localhost -U postgres -d identity -tAc "select count(*) from audit_events where action = 'UNDERAGE_SIGNUP_REFUSED' and resource_id is null and actor_id is null"
Check 'refusal audited without personal data' ([int]$auditRow.Trim() -ge 1)
$t2 = Api GET '/api/v1/me' $teen
Check 'old token cannot recreate the account (403 ACCOUNT_REMOVED)' ($t2.__code -eq 'ACCOUNT_REMOVED') ($t2 | ConvertTo-Json -Compress)

# --- 18th birthday today ---
$dob18 = (Get-Date).AddYears(-18).ToString('yyyy-MM-dd')
$e1 = Api PUT '/api/v1/me/profile' $eighteen @{ displayName = 'Eighteen Test'; phone = ''; preferredLanguage = 'en'; birthDate = $dob18; gender = 'UNDISCLOSED'; confirmAdult = $true }
Check '18th birthday today is accepted' ($e1.profileComplete -eq $true) ($e1 | ConvertTo-Json -Compress)

""
"RESULT: $pass passed, $fail failed"
