# DEV/TEST ONLY: removes E2E donations (campaign or purpose starting with "E2E ") together with their receipts and the
# receipts' entries in the document vault. Used by test-donations.ps1 and test-receipts.ps1.
# Also removes orphan receipts: a donation.paid event can reach receipt-service after the donation was cleaned up
# (e.g. it was queued while receipt-service was down), leaving a receipt that fails with "Donation not found".
$psql = 'D:\tools\pgsql\bin\psql.exe'
$env:PGPASSWORD = 'dev-only-password'
$where = "campaign_title like 'E2E %' or purpose like 'E2E %' or donor_name like 'E2E %'"
function Uuids([string]$db, [string]$q) {
  @(& $psql -w -h localhost -U postgres -d $db -t -A -c $q 2>$null | Where-Object { $_ -match '^[0-9a-f-]{36}$' })
}
function Remove-Receipts([string[]]$donationIds) {
  if ($donationIds.Count -eq 0) { return }
  $list = ($donationIds | ForEach-Object { "('$_')" }) -join ','
  $rids = Uuids 'receipt' "select id from donation_receipts where donation_id in (select v::uuid from (values $list) t(v))"
  if ($rids.Count -gt 0) {
    $rlist = ($rids | ForEach-Object { "'$_'" }) -join ','
    & $psql -w -h localhost -U postgres -d document -q -c "BEGIN; ALTER TABLE document_activity DISABLE TRIGGER trg_document_activity_immutable; delete from document_activity where document_id in (select id from documents where source = 'RECEIPT' and source_ref in ($rlist)); delete from documents where source = 'RECEIPT' and source_ref in ($rlist); ALTER TABLE document_activity ENABLE TRIGGER trg_document_activity_immutable; COMMIT;" 2>$null | Out-Null
  }
  $sql = "CREATE TEMP TABLE e2e_donations AS SELECT v::uuid AS id FROM (VALUES $list) t(v);`n" + (Get-Content "$PSScriptRoot\sql\cleanup-e2e-receipts.sql" -Raw)
  $tmp = Join-Path $env:TEMP 'cleanup-e2e-receipts.sql'
  Set-Content -Path $tmp -Value $sql -Encoding ASCII
  & $psql -w -h localhost -U postgres -d receipt -q -f $tmp 2>$null | Out-Null
}
$ids = Uuids 'donation' "select id from donations where $where"
Remove-Receipts $ids
& $psql -w -h localhost -U postgres -d donation -q -c "delete from donation_payments where donation_id in (select id from donations where $where); update donations set correction_of = null where correction_of in (select id from donations where $where); delete from donations where $where;" | Out-Null
$all = @{}; foreach ($d in (Uuids 'donation' 'select id from donations')) { $all[$d] = $true }
$orphans = @(Uuids 'receipt' 'select distinct donation_id from donation_receipts' | Where-Object { -not $all.ContainsKey($_) })
Remove-Receipts $orphans
"e2e donations removed: $($ids.Count); orphan receipts removed: $($orphans.Count)"
