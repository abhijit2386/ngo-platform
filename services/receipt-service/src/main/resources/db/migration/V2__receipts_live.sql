-- Receipts R1 (owner decisions 2026-11-01): receipts printed in English; donor name exactly as in the donor's profile
-- (snapshot taken by donation-service at checkout); numbers PREFIX/<FY>/000001 restarting every financial year;
-- signatories (chairman, secretary ...) with signature images; QR code to verify a receipt.

-- donor-service (NGO donor CRM) is not running yet: the donation's own donor snapshot is used.
ALTER TABLE donation_receipts ALTER COLUMN donor_id DROP NOT NULL;
ALTER TABLE donation_receipts
    ADD COLUMN donor_name    VARCHAR(160),
    ADD COLUMN ngo_name      VARCHAR(160),
    ADD COLUMN donation_date DATE,
    ADD COLUMN fiscal_year   VARCHAR(7),
    ADD COLUMN channel       VARCHAR(20),
    ADD COLUMN campaign_title VARCHAR(120),
    ADD COLUMN verify_code   VARCHAR(16),
    ADD COLUMN pdf_size      BIGINT,
    ADD COLUMN notified_at   TIMESTAMPTZ,
    ADD COLUMN void_by       UUID,
    ADD COLUMN voided_at     TIMESTAMPTZ;
CREATE UNIQUE INDEX uq_receipt_verify_code ON donation_receipts (verify_code) WHERE verify_code IS NOT NULL;
CREATE INDEX ix_receipt_ngo_fy ON donation_receipts (ngo_id, fiscal_year, created_at DESC);
CREATE INDEX ix_receipt_notify ON donation_receipts (status, notified_at) WHERE status = 'DELIVERY_PENDING';

-- An issued receipt is a financial record: number, file, amount and printed name never change afterwards.
-- Corrections are a cancellation + a new receipt (reissue).
CREATE OR REPLACE FUNCTION protect_issued_receipt() RETURNS trigger AS $$
BEGIN
    -- The number is taken first (gap-free, reused by retries); the PDF is stored afterwards.
    IF OLD.receipt_number IS NOT NULL AND (
           NEW.receipt_number IS DISTINCT FROM OLD.receipt_number
        OR NEW.verify_code IS DISTINCT FROM OLD.verify_code
        OR NEW.amount IS DISTINCT FROM OLD.amount
        OR NEW.donor_name IS DISTINCT FROM OLD.donor_name
        OR NEW.ngo_id IS DISTINCT FROM OLD.ngo_id
        OR NEW.donation_id IS DISTINCT FROM OLD.donation_id) THEN
        RAISE EXCEPTION 'receipt % is immutable', OLD.receipt_number;
    END IF;
    IF OLD.pdf_key IS NOT NULL AND (NEW.pdf_key IS DISTINCT FROM OLD.pdf_key OR NEW.pdf_sha256 IS DISTINCT FROM OLD.pdf_sha256) THEN
        RAISE EXCEPTION 'issued receipt % file is immutable', OLD.receipt_number;
    END IF;
    IF OLD.status IN ('VOIDED', 'REISSUED') AND NEW.status IS DISTINCT FROM OLD.status THEN
        RAISE EXCEPTION 'cancelled receipt % cannot be reactivated', OLD.receipt_number;
    END IF;
    RETURN NEW;
END $$ LANGUAGE plpgsql;
CREATE TRIGGER trg_receipt_immutable BEFORE UPDATE ON donation_receipts FOR EACH ROW EXECUTE FUNCTION protect_issued_receipt();
CREATE TRIGGER trg_receipt_no_delete BEFORE DELETE ON donation_receipts FOR EACH ROW EXECUTE FUNCTION forbid_mutation();

-- Per-NGO receipt settings (NGO admins).
CREATE TABLE receipt_settings (
    ngo_id          UUID PRIMARY KEY,
    prefix          VARCHAR(10)  NOT NULL CHECK (prefix ~ '^[A-Z0-9]{2,10}$'),
    reg_12a_number  VARCHAR(60),
    thank_you_note  VARCHAR(200),
    updated_by      UUID,
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version         BIGINT       NOT NULL DEFAULT 0
);

-- Up to 3 signatories appear on the standard receipt (chairman, secretary, treasurer ...), in position order.
CREATE TABLE receipt_signatories (
    id             UUID PRIMARY KEY,
    ngo_id         UUID         NOT NULL,
    name           VARCHAR(80)  NOT NULL,
    designation    VARCHAR(60)  NOT NULL,
    signature_key  VARCHAR(300),
    position       INT          NOT NULL DEFAULT 0,
    active         BOOLEAN      NOT NULL DEFAULT true,
    created_by     UUID         NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version        BIGINT       NOT NULL DEFAULT 0
);
CREATE INDEX ix_signatory_ngo ON receipt_signatories (ngo_id, position);

-- One-time import of donations paid before receipt-service ran.
CREATE TABLE backfill_state (
    name        VARCHAR(40) PRIMARY KEY,
    done_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
