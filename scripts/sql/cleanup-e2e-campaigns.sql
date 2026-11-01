-- DEV/TEST ONLY: removes campaigns titled "E2E ..." and everything hanging off them (used by the E2E scripts).
-- Financial rows are immutable by trigger; the triggers are switched off inside this transaction only.
BEGIN;
ALTER TABLE staff_money_entries DISABLE TRIGGER trg_staff_money_immutable;
ALTER TABLE campaign_expenses DISABLE TRIGGER trg_campaign_expense_guard;
CREATE TEMP TABLE e2e_c ON COMMIT DROP AS SELECT id FROM campaigns WHERE title LIKE 'E2E %';
DELETE FROM staff_money_entries WHERE campaign_id IN (SELECT id FROM e2e_c)
    OR expense_id IN (SELECT id FROM campaign_expenses WHERE campaign_id IN (SELECT id FROM e2e_c))
    OR note LIKE 'E2E %';
DELETE FROM expense_attachments WHERE expense_id IN (SELECT id FROM campaign_expenses WHERE campaign_id IN (SELECT id FROM e2e_c));
DELETE FROM campaign_expenses WHERE campaign_id IN (SELECT id FROM e2e_c);
DELETE FROM campaign_budget_lines WHERE campaign_id IN (SELECT id FROM e2e_c);
DELETE FROM campaign_donations WHERE campaign_id IN (SELECT id FROM e2e_c);
DELETE FROM campaigns WHERE id IN (SELECT id FROM e2e_c);
DELETE FROM vendors WHERE name LIKE 'E2E %' AND id NOT IN (SELECT vendor_id FROM campaign_expenses WHERE vendor_id IS NOT NULL);
ALTER TABLE campaign_expenses ENABLE TRIGGER trg_campaign_expense_guard;
ALTER TABLE staff_money_entries ENABLE TRIGGER trg_staff_money_immutable;
COMMIT;
