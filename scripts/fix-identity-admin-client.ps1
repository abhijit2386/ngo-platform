# DEV ONLY. Creates the confidential client "ngo-identity-admin" in the RUNNING local realm (the realm file already
# has it for fresh imports) and grants its service account realm-management manage-users + view-users.
# identity-service uses it to delete refused under-18 sign-ups (and, later, deleted accounts). Safe to re-run.
$ErrorActionPreference = 'Stop'
$kc = 'http://localhost:8180'
$tok = (Invoke-RestMethod -Method Post "$kc/realms/master/protocol/openid-connect/token" -Body @{
  grant_type = 'password'; client_id = 'admin-cli'; username = 'admin'; password = 'dev-only-admin' }).access_token
$h = @{ Authorization = "Bearer $tok" }
$existing = Invoke-RestMethod "$kc/admin/realms/ngo/clients?clientId=ngo-identity-admin" -Headers $h
if (-not $existing) {
  $body = @{ clientId = 'ngo-identity-admin'; name = 'identity-service admin access (manage-users only)'; enabled = $true
    publicClient = $false; clientAuthenticatorType = 'client-secret'; secret = 'dev-only-identity-admin-secret'
    standardFlowEnabled = $false; directAccessGrantsEnabled = $false; serviceAccountsEnabled = $true } | ConvertTo-Json
  Invoke-RestMethod -Method Post "$kc/admin/realms/ngo/clients" -Headers $h -ContentType 'application/json' -Body $body | Out-Null
  $existing = Invoke-RestMethod "$kc/admin/realms/ngo/clients?clientId=ngo-identity-admin" -Headers $h
  "client created"
} else { "client exists" }
$cid = @($existing)[0].id
$sa = Invoke-RestMethod "$kc/admin/realms/ngo/clients/$cid/service-account-user" -Headers $h
$rm = @(Invoke-RestMethod "$kc/admin/realms/ngo/clients?clientId=realm-management" -Headers $h)[0]
$roles = @('manage-users', 'view-users') | ForEach-Object { Invoke-RestMethod "$kc/admin/realms/ngo/clients/$($rm.id)/roles/$_" -Headers $h }
$body = ConvertTo-Json -InputObject @($roles) -Depth 4
Invoke-RestMethod -Method Post "$kc/admin/realms/ngo/users/$($sa.id)/role-mappings/clients/$($rm.id)" -Headers $h -ContentType 'application/json' -Body $body | Out-Null
"service account granted: manage-users, view-users"
$t = Invoke-RestMethod -Method Post "$kc/realms/ngo/protocol/openid-connect/token" -Body @{
  grant_type = 'client_credentials'; client_id = 'ngo-identity-admin'; client_secret = 'dev-only-identity-admin-secret' }
"client_credentials token ok: $([bool]$t.access_token)"
