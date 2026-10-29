# Creates the fictional test users from infrastructure\keycloak\test-users.json in the LOCAL Keycloak (realm ngo).
# Uses the local bootstrap admin; safe to re-run (existing users are skipped).
$ErrorActionPreference = 'Stop'
$kc = 'http://localhost:8180'
$seed = Get-Content 'D:\ngo-platform\infrastructure\keycloak\test-users.json' -Raw | ConvertFrom-Json
$tok = (Invoke-RestMethod -Method Post "$kc/realms/master/protocol/openid-connect/token" -Body @{
  grant_type = 'password'; client_id = 'admin-cli'; username = 'admin'; password = 'dev-only-admin' }).access_token
$h = @{ Authorization = "Bearer $tok" }
foreach ($u in $seed.users) {
  $existing = Invoke-RestMethod "$kc/admin/realms/ngo/users?email=$([uri]::EscapeDataString($u.email))&exact=true" -Headers $h
  if ($existing.Count -gt 0) { "exists  $($u.email)"; continue }
  $body = @{ username = $u.email; email = $u.email; firstName = $u.firstName; lastName = $u.lastName; enabled = $true;
             emailVerified = $true; credentials = @(@{ type = 'password'; value = $seed.password; temporary = $false }) } | ConvertTo-Json -Depth 4
  Invoke-RestMethod -Method Post "$kc/admin/realms/ngo/users" -Headers $h -ContentType 'application/json' -Body $body | Out-Null
  $id = (Invoke-RestMethod "$kc/admin/realms/ngo/users?email=$([uri]::EscapeDataString($u.email))&exact=true" -Headers $h)[0].id
  $roles = @((Invoke-RestMethod "$kc/admin/realms/ngo/roles/DONOR" -Headers $h), (Invoke-RestMethod "$kc/admin/realms/ngo/roles/offline_access" -Headers $h))
  Invoke-RestMethod -Method Post "$kc/admin/realms/ngo/users/$id/role-mappings/realm" -Headers $h -ContentType 'application/json' -Body (ConvertTo-Json -InputObject $roles -Depth 4) | Out-Null
  "created $($u.email)"
}
