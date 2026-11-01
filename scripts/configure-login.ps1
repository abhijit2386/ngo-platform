# Applies the master configuration (config\yogdaan.env) to the login server (Keycloak realm "ngo"):
#   - sign-in page title "Yogdaan"
#   - e-mail account used for password-reset / verification e-mails (same SMTP as notification-service)
#   - "Sign in with Google" / "Sign in with Facebook" buttons when their keys are filled in (removed again when blank)
# Safe to run any number of times. Prints no secrets.
#   powershell -File scripts\configure-login.ps1 [-ConfigFile path]   (server: -ConfigFile /etc/yogdaan/yogdaan.env)
param([string]$ConfigFile = "$PSScriptRoot\..\config\yogdaan.env")
$ErrorActionPreference = 'Stop'

$c = @{}
if (Test-Path $ConfigFile) {
  foreach ($line in Get-Content $ConfigFile) {
    if ($line -match '^\s*([A-Z][A-Z0-9_]*)\s*=\s*(.*?)\s*$' -and $Matches[2] -ne '') { $c[$Matches[1]] = $Matches[2] }
  }
  "using $ConfigFile ($($c.Count) settings)"
} else { "no $ConfigFile - using development defaults" }
function V([string]$k, [string]$def = '') { if ($c.ContainsKey($k)) { $c[$k] } else { $def } }

$kc = (V 'KEYCLOAK_URL' 'http://localhost:8180').TrimEnd('/')
$realm = V 'KEYCLOAK_REALM' 'ngo'
$tok = Invoke-RestMethod -Method Post -Uri "$kc/realms/master/protocol/openid-connect/token" -ContentType 'application/x-www-form-urlencoded' `
  -Body @{ grant_type = 'password'; client_id = 'admin-cli'; username = (V 'KEYCLOAK_ADMIN_USER' 'admin'); password = (V 'KEYCLOAK_ADMIN_PASSWORD' 'dev-only-admin') }
$h = @{ Authorization = "Bearer $($tok.access_token)" }
$base = "$kc/admin/realms/$realm"
function Send($method, $url, $body) {
  if ($null -eq $body) { return Invoke-RestMethod -Method $method -Uri $url -Headers $h }
  # byte[] kept in a variable: passing it through $( ) would unroll it into a list of numbers (PowerShell 5.1).
  [byte[]]$bytes = [Text.Encoding]::UTF8.GetBytes(($body | ConvertTo-Json -Depth 6))
  Invoke-RestMethod -Method $method -Uri $url -Headers $h -ContentType 'application/json; charset=utf-8' -Body $bytes
}

# 1. Title and e-mail account
$auth = (V 'MAIL_AUTH' 'false') -eq 'true'
$smtp = @{ host = (V 'MAIL_HOST' 'localhost'); port = (V 'MAIL_PORT' '1025'); from = (V 'MAIL_FROM' 'no-reply@yogdaan.co.in')
  fromDisplayName = (V 'MAIL_FROM_NAME' 'Yogdaan'); auth = "$auth".ToLower(); starttls = (V 'MAIL_STARTTLS' 'false') }
if ($auth) { $smtp.user = V 'MAIL_USER'; $smtp.password = V 'MAIL_PASSWORD' }
Send 'Put' $base @{ displayName = 'Yogdaan'; displayNameHtml = 'Yogdaan'; smtpServer = $smtp } | Out-Null
"login page title: Yogdaan; e-mail: $($smtp.host):$($smtp.port) as $($smtp.from)"

# 2. Google / Facebook sign-in
function Provider([string]$alias, [string]$id, [string]$secret, [string]$scope) {
  $url = "$base/identity-provider/instances/$alias"
  $exists = $true
  try { Send 'Get' $url $null | Out-Null } catch { $exists = $false }
  if ([string]::IsNullOrEmpty($id) -or [string]::IsNullOrEmpty($secret)) {
    if ($exists) { Send 'Delete' $url $null | Out-Null; "$alias sign-in: removed (no keys in the configuration)" } else { "$alias sign-in: off (no keys)" }
    return
  }
  $body = @{ alias = $alias; providerId = $alias; enabled = $true; trustEmail = $true; storeToken = $false
    firstBrokerLoginFlowAlias = 'first broker login'
    config = @{ clientId = $id; clientSecret = $secret; defaultScope = $scope; syncMode = 'IMPORT' } }
  if ($exists) { Send 'Put' $url $body | Out-Null; "$alias sign-in: updated" }
  else { Send 'Post' "$base/identity-provider/instances" $body | Out-Null; "$alias sign-in: added" }
  "  redirect URI to register with $alias : $kc/realms/$realm/broker/$alias/endpoint"
}
Provider 'google' (V 'GOOGLE_CLIENT_ID') (V 'GOOGLE_CLIENT_SECRET') 'openid profile email'
Provider 'facebook' (V 'FACEBOOK_APP_ID') (V 'FACEBOOK_APP_SECRET') 'email public_profile'
"done"
