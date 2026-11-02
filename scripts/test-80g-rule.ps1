# DEV/TEST ONLY: owner rule (2 Nov 2026) - a receipt is an 80G receipt when the NGO's 80G registration is verified and
# valid on the donation date, whatever the donor chose and even without a PAN. Uses offline donations recorded by
# Sunil (standard dataset) with purpose "E2E 80G rule"; cleans them up with cleanup-e2e-donations.ps1 and restores the
# NGO's 80G validity (seed-donations sets 31 Mar 2031).
param([string]$NgoId = '0cf945f6-1183-42f5-aeb1-20bab9be302b')
$ErrorActionPreference = 'Stop'
$api = 'http://localhost:8080'; $kc = 'http://localhost:8180/realms/ngo/protocol/openid-connect/token'
$psql = 'D:\tools\pgsql\bin\psql.exe'
$env:PGPASSWORD = 'dev-only-password'
$pass = 0; $fail = 0
function Check([string]$name, [bool]$ok, $detail = '') {
  if ($ok) { $script:pass++; "PASS  $name" } else { $script:fail++; "FAIL  $name  $detail" }
}
function Token($user, $pw) {
  (Invoke-RestMethod -Method Post $kc -Body @{ grant_type = 'password'; client_id = 'ngo-admin-cli'; username = $user; password = $pw; scope = 'openid' }).access_token
}
function Sql([string]$db, [string]$q) { @(& $psql -w -h localhost -U postgres -d $db -t -A -F '|' -c $q 2>$null | Where-Object { $_ -ne '' }) }
function One([string]$db, [string]$q) { [string](@(Sql $db $q) | Select-Object -First 1) }
function Record($tok, $name, $amount) {
  $b = @{ donorName = $name; amount = $amount; channel = 'CASH'; wants80g = $false; purpose = 'E2E 80G rule'; receivedOn = (Get-Date).ToString('yyyy-MM-dd') } | ConvertTo-Json
  Invoke-RestMethod -Method Post "$api/api/v1/ngos/$NgoId/donations/offline" -Headers @{ Authorization = "Bearer $tok" } -ContentType 'application/json' -Body $b
}
# Waits for the receipt and returns "tax_80g|receipt_number".
function ReceiptOf($donationId) {
  for ($i = 0; $i -lt 60; $i++) {
    $r = One 'receipt' "select tax_80g, receipt_number from donation_receipts where donation_id = '$donationId' and receipt_number is not null and status not in ('VOIDED','REISSUED') order by created_at desc limit 1"
    if ($r) { return $r }
    Start-Sleep 3
  }
  return ''
}

$sunil = Token 'sunil.staff@ngo.test' 'DevTest#2026'
$valid = One 'ngo_registry' "select tax_80g_valid_until from ngos where id = '$NgoId'"
"NGO 80G valid until: $valid"
try {
  $d1 = Record $sunil 'E2E Walk-in Donor' '300.00'
  $r1 = ReceiptOf $d1.id
  Check 'NGO with valid 80G: receipt is 80G although the donor did not ask and gave no PAN' ($r1 -like 't|*') $r1

  $null = Sql 'ngo_registry' "update ngos set tax_80g_valid_until = current_date - 1 where id = '$NgoId'"
  Start-Sleep 2
  $d2 = Record $sunil 'E2E Walk-in Donor Two' '400.00'
  $r2 = ReceiptOf $d2.id
  Check 'NGO whose 80G expired before the donation date: plain receipt' ($r2 -like 'f|*') $r2
} finally {
  $null = Sql 'ngo_registry' "update ngos set tax_80g_valid_until = '$valid' where id = '$NgoId'"
  "80G validity restored: $(One 'ngo_registry' "select tax_80g_valid_until from ngos where id = '$NgoId'")"
  & "$PSScriptRoot\cleanup-e2e-donations.ps1"
}
""
"80g rule e2e: $pass passed, $fail failed"
