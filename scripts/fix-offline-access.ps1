# Grants the offline_access realm role (needed for the mobile app's long-lived sign-in) to every user in the
# LOCAL realm and adds it to the realm's default roles for future registrations. Safe to re-run.
$ErrorActionPreference = 'Stop'
$kc = 'http://localhost:8180'
$tok = (Invoke-RestMethod -Method Post "$kc/realms/master/protocol/openid-connect/token" -Body @{
  grant_type = 'password'; client_id = 'admin-cli'; username = 'admin'; password = 'dev-only-admin' }).access_token
$h = @{ Authorization = "Bearer $tok" }
$role = Invoke-RestMethod "$kc/admin/realms/ngo/roles/offline_access" -Headers $h
$body = ConvertTo-Json -InputObject @($role) -Depth 4
$def = Invoke-RestMethod "$kc/admin/realms/ngo/roles/default-roles-ngo" -Headers $h
Invoke-RestMethod -Method Post "$kc/admin/realms/ngo/roles-by-id/$($def.id)/composites" -Headers $h -ContentType 'application/json' -Body $body | Out-Null
"default-roles-ngo now includes offline_access"
foreach ($u in (Invoke-RestMethod "$kc/admin/realms/ngo/users?max=500" -Headers $h)) {
  Invoke-RestMethod -Method Post "$kc/admin/realms/ngo/users/$($u.id)/role-mappings/realm" -Headers $h -ContentType 'application/json' -Body $body | Out-Null
  "offline_access -> $($u.username)"
}
