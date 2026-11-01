-- Donation receipts (receipt-service, 2026-11-01) appear in the vault (category Receipts) linked to their receipt.
ALTER TABLE documents DROP CONSTRAINT IF EXISTS documents_link_type_check;
ALTER TABLE documents ADD CONSTRAINT documents_link_type_check
    CHECK (link_type IN ('CAMPAIGN','EXPENSE','PROJECT','MEETING','NGO','RECEIPT'));
