-- DEV/TEST ONLY (receipt DB): removes the receipts of donations listed in temp table e2e_donations(id uuid), which the
-- caller fills first. Receipts are normally undeletable (financial records); the guard triggers are disabled inside
-- this transaction only.
BEGIN;
ALTER TABLE donation_receipts DISABLE TRIGGER trg_receipt_no_delete;
ALTER TABLE donation_receipts DISABLE TRIGGER trg_receipt_immutable;
ALTER TABLE receipt_events DISABLE TRIGGER receipt_events_append_only;
CREATE TEMP TABLE e2e_r ON COMMIT DROP AS SELECT id FROM donation_receipts WHERE donation_id IN (SELECT id FROM e2e_donations);
DELETE FROM receipt_events WHERE receipt_id IN (SELECT id FROM e2e_r);
UPDATE donation_receipts SET reissue_of = NULL WHERE reissue_of IN (SELECT id FROM e2e_r);
DELETE FROM donation_receipts WHERE id IN (SELECT id FROM e2e_r);
ALTER TABLE receipt_events ENABLE TRIGGER receipt_events_append_only;
ALTER TABLE donation_receipts ENABLE TRIGGER trg_receipt_immutable;
ALTER TABLE donation_receipts ENABLE TRIGGER trg_receipt_no_delete;
COMMIT;
