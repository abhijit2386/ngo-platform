-- DEV/TEST ONLY: removes the sample accounts of "School books for 200 children" so seed-expenses.ps1 can recreate them.
BEGIN;
ALTER TABLE staff_money_entries DISABLE TRIGGER trg_staff_money_immutable;
ALTER TABLE campaign_expenses DISABLE TRIGGER trg_campaign_expense_guard;
CREATE TEMP TABLE seed_c ON COMMIT DROP AS SELECT id FROM campaigns WHERE title = 'School books for 200 children';
DELETE FROM staff_money_entries WHERE campaign_id IN (SELECT id FROM seed_c)
    OR expense_id IN (SELECT id FROM campaign_expenses WHERE campaign_id IN (SELECT id FROM seed_c));
DELETE FROM expense_attachments WHERE expense_id IN (SELECT id FROM campaign_expenses WHERE campaign_id IN (SELECT id FROM seed_c));
DELETE FROM campaign_expenses WHERE campaign_id IN (SELECT id FROM seed_c);
DELETE FROM campaign_budget_lines WHERE campaign_id IN (SELECT id FROM seed_c);
ALTER TABLE campaign_expenses ENABLE TRIGGER trg_campaign_expense_guard;
ALTER TABLE staff_money_entries ENABLE TRIGGER trg_staff_money_immutable;
COMMIT;
