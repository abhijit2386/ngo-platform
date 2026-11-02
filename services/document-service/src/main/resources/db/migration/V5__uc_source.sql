-- Utilisation certificates (campaign-project-service, CSR 2026-11-02) appear in the vault, category CSR, linked to
-- their campaign.
ALTER TABLE documents DROP CONSTRAINT IF EXISTS documents_source_check;
ALTER TABLE documents ADD CONSTRAINT documents_source_check
    CHECK (source IN ('UPLOAD','REGISTRATION','EXPENSE_BILL','RECEIPT','MEETING_MINUTES','UTILISATION_CERT'));
