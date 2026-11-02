-- Utilisation certificates for CSR funders (Enterprise: CSR_UTILISATION_CERT, owner decision 2026-11-02).
-- A UC is prepared from the campaign's own accounts (approved expenses in the period, by budget head). The NGO signs
-- it, which freezes the figures and opens a link for its chartered accountant; the CA certifies through that link
-- (name, membership no., firm, UDIN) and the final PDF goes to the document vault (category CSR).
CREATE TABLE utilisation_certificates (
    id                 UUID PRIMARY KEY,
    ngo_id             UUID          NOT NULL,
    campaign_id        UUID          NOT NULL REFERENCES campaigns(id),
    uc_number          VARCHAR(30)   NOT NULL,
    fiscal_year        VARCHAR(7)    NOT NULL,
    funder_name        VARCHAR(160)  NOT NULL,
    funder_reference   VARCHAR(120),
    csr1_number        VARCHAR(40),
    grant_amount       NUMERIC(14,2) NOT NULL CHECK (grant_amount > 0),
    grant_received_on  DATE          NOT NULL,
    period_from        DATE          NOT NULL,
    period_to          DATE          NOT NULL,
    status             VARCHAR(14)   NOT NULL DEFAULT 'DRAFT'
                           CHECK (status IN ('DRAFT','NGO_SIGNED','CA_CERTIFIED','CANCELLED')),
    -- Frozen when the NGO signs: totals, heads and the expense list exactly as certified (JSON).
    figures            JSONB,
    signatory_name     VARCHAR(120),
    signatory_role     VARCHAR(80),
    ngo_signed_by      UUID,
    ngo_signed_at      TIMESTAMPTZ,
    ca_code            VARCHAR(40)   UNIQUE,
    ca_link_expires_at TIMESTAMPTZ,
    ca_name            VARCHAR(120),
    ca_membership_no   VARCHAR(10),
    ca_firm            VARCHAR(160),
    ca_firm_reg_no     VARCHAR(12),
    ca_udin            VARCHAR(18),
    ca_place           VARCHAR(80),
    ca_certified_at    TIMESTAMPTZ,
    pdf_key            VARCHAR(300),
    pdf_sha256         CHAR(64),
    cancel_reason      VARCHAR(300),
    cancelled_by       UUID,
    cancelled_at       TIMESTAMPTZ,
    created_by         UUID          NOT NULL,
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version            BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT uq_uc_number UNIQUE (ngo_id, uc_number),
    CHECK (period_to >= period_from),
    CHECK (status = 'DRAFT' OR status = 'CANCELLED' OR (figures IS NOT NULL AND ngo_signed_at IS NOT NULL)),
    CHECK (status <> 'CA_CERTIFIED' OR (ca_udin IS NOT NULL AND pdf_key IS NOT NULL))
);
CREATE INDEX ix_uc_ngo ON utilisation_certificates (ngo_id, created_at DESC);
CREATE INDEX ix_uc_campaign ON utilisation_certificates (campaign_id, created_at DESC);

-- UC numbers per NGO and financial year: UC/2026-27/001 (gap-free, like receipts and vouchers).
CREATE TABLE uc_counters (
    ngo_id      UUID       NOT NULL,
    fiscal_year VARCHAR(7) NOT NULL,
    next_value  INT        NOT NULL DEFAULT 1,
    PRIMARY KEY (ngo_id, fiscal_year)
);

-- Once signed, the figures cannot change; once certified, a UC can only be cancelled; nothing is ever deleted.
CREATE FUNCTION uc_guard() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'utilisation_certificates: certificates are never deleted (cancel instead)';
    END IF;
    IF OLD.status = 'CA_CERTIFIED' AND (NEW.status <> 'CANCELLED' OR NEW.figures IS DISTINCT FROM OLD.figures
            OR NEW.ca_udin IS DISTINCT FROM OLD.ca_udin OR NEW.pdf_key IS DISTINCT FROM OLD.pdf_key
            OR NEW.grant_amount IS DISTINCT FROM OLD.grant_amount) THEN
        RAISE EXCEPTION 'utilisation_certificates: a certified UC can only be cancelled';
    END IF;
    IF OLD.status IN ('NGO_SIGNED','CA_CERTIFIED') AND (NEW.figures IS DISTINCT FROM OLD.figures
            OR NEW.grant_amount IS DISTINCT FROM OLD.grant_amount OR NEW.period_from IS DISTINCT FROM OLD.period_from
            OR NEW.period_to IS DISTINCT FROM OLD.period_to OR NEW.funder_name IS DISTINCT FROM OLD.funder_name
            OR NEW.uc_number IS DISTINCT FROM OLD.uc_number) THEN
        RAISE EXCEPTION 'utilisation_certificates: signed figures are frozen';
    END IF;
    IF OLD.status = 'CANCELLED' AND NEW IS DISTINCT FROM OLD THEN
        RAISE EXCEPTION 'utilisation_certificates: a cancelled UC cannot change';
    END IF;
    RETURN NEW;
END $$ LANGUAGE plpgsql;
CREATE TRIGGER trg_uc_guard BEFORE UPDATE OR DELETE ON utilisation_certificates
    FOR EACH ROW EXECUTE FUNCTION uc_guard();
