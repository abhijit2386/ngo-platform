-- NGO payment configuration. Funds settle to the NGO's own merchant account; the platform never holds donations.
CREATE TABLE payment_settings (
    ngo_id                 UUID PRIMARY KEY,
    provider               VARCHAR(20)   NOT NULL CHECK (provider IN ('MOCK','RAZORPAY','CASHFREE','PAYU','OTHER')),
    merchant_reference     VARCHAR(100)  NOT NULL,
    status                 VARCHAR(20)   NOT NULL CHECK (status IN ('PENDING','ACTIVE','SUSPENDED')),
    guest_checkout_allowed BOOLEAN       NOT NULL DEFAULT true,
    min_amount             NUMERIC(12,2) NOT NULL DEFAULT 10 CHECK (min_amount > 0),
    max_amount             NUMERIC(12,2) NOT NULL DEFAULT 1000000 CHECK (max_amount >= min_amount),
    receipt_prefix         VARCHAR(10)   NOT NULL DEFAULT 'RCPT' CHECK (receipt_prefix ~ '^[A-Z0-9]{2,10}$'),
    updated_by             UUID,
    updated_at             TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version                BIGINT        NOT NULL DEFAULT 0
);

CREATE TABLE donations (
    id                UUID PRIMARY KEY,
    ngo_id            UUID          NOT NULL,
    donor_id          UUID          NOT NULL,          -- donor-service record (NGO scoped)
    donor_user_id     UUID,                            -- platform donor account, null for guests/offline
    donor_name        VARCHAR(160)  NOT NULL,
    donor_email       VARCHAR(254),
    donor_pan         VARCHAR(10),
    campaign_id       UUID,
    project_id        UUID,
    amount            NUMERIC(14,2) NOT NULL CHECK (amount > 0),
    refunded_amount   NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (refunded_amount >= 0),
    currency          CHAR(3)       NOT NULL DEFAULT 'INR' CHECK (currency = 'INR'),
    purpose           VARCHAR(200),
    channel           VARCHAR(20)   NOT NULL CHECK (channel IN ('ONLINE','CASH','CHEQUE','BANK_TRANSFER','UPI_DIRECT','IN_KIND_VALUE')),
    status            VARCHAR(20)   NOT NULL CHECK (status IN ('CREATED','PENDING','AUTHORIZED','PAID','FAILED','CANCELLED','REFUNDED','PARTIALLY_REFUNDED','DISPUTED')),
    wants_80g         BOOLEAN       NOT NULL DEFAULT false,
    offline_reference VARCHAR(100),
    evidence_key      VARCHAR(300),
    recorded_by       UUID,
    paid_at           TIMESTAMPTZ,
    failure_reason    VARCHAR(200),
    voided            BOOLEAN       NOT NULL DEFAULT false,
    correction_of     UUID REFERENCES donations(id),
    expires_at        TIMESTAMPTZ,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version           BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT ck_refund_le_amount CHECK (refunded_amount <= amount),
    CONSTRAINT ck_paid_at CHECK (status NOT IN ('PAID','REFUNDED','PARTIALLY_REFUNDED') OR paid_at IS NOT NULL)
);
CREATE INDEX ix_donation_ngo_time ON donations (ngo_id, created_at DESC);
CREATE INDEX ix_donation_status ON donations (status, created_at);
CREATE INDEX ix_donation_user ON donations (donor_user_id, created_at DESC);
CREATE INDEX ix_donation_campaign ON donations (campaign_id);

CREATE TABLE donation_payments (
    id                  UUID PRIMARY KEY,
    donation_id         UUID          NOT NULL REFERENCES donations(id),
    ngo_id              UUID          NOT NULL,
    provider            VARCHAR(20)   NOT NULL,
    provider_order_id   VARCHAR(100)  NOT NULL UNIQUE,
    provider_payment_id VARCHAR(100)  UNIQUE,
    amount              NUMERIC(14,2) NOT NULL CHECK (amount > 0),
    scenario            VARCHAR(30),
    status              VARCHAR(20)   NOT NULL,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version             BIGINT        NOT NULL DEFAULT 0
);
CREATE INDEX ix_dpay_donation ON donation_payments (donation_id);

-- Raw provider events (append-only); provider_event_id makes duplicate webhooks no-ops.
CREATE TABLE payment_events (
    id                UUID PRIMARY KEY,
    provider          VARCHAR(20)  NOT NULL,
    provider_event_id VARCHAR(120) NOT NULL,
    donation_id       UUID,
    event_type        VARCHAR(40)  NOT NULL,
    signature_valid   BOOLEAN      NOT NULL,
    outcome           VARCHAR(30)  NOT NULL,
    payload           JSONB        NOT NULL,
    received_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_provider_event UNIQUE (provider, provider_event_id)
);
CREATE INDEX ix_pevent_donation ON payment_events (donation_id, received_at);

CREATE TABLE refunds (
    id                 UUID PRIMARY KEY,
    donation_id        UUID          NOT NULL REFERENCES donations(id),
    ngo_id             UUID          NOT NULL,
    amount             NUMERIC(14,2) NOT NULL CHECK (amount > 0),
    reason             VARCHAR(500)  NOT NULL,
    status             VARCHAR(20)   NOT NULL CHECK (status IN ('REQUESTED','APPROVED','REJECTED','PROCESSING','SUCCEEDED','FAILED')),
    requested_by       UUID          NOT NULL,
    decided_by         UUID,
    provider_refund_id VARCHAR(100),
    failure_reason     VARCHAR(200),
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version            BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT ck_maker_checker CHECK (decided_by IS NULL OR decided_by <> requested_by)
);
CREATE INDEX ix_refund_ngo ON refunds (ngo_id, status);

CREATE TABLE settlement_batches (
    id                    UUID PRIMARY KEY,
    ngo_id                UUID          NOT NULL,
    provider              VARCHAR(20)   NOT NULL,
    provider_settlement_id VARCHAR(100) NOT NULL,
    settled_on            DATE          NOT NULL,
    gross_amount          NUMERIC(14,2) NOT NULL,
    fee_amount            NUMERIC(14,2) NOT NULL,
    tax_amount            NUMERIC(14,2) NOT NULL,
    net_amount            NUMERIC(14,2) NOT NULL,
    bank_reference        VARCHAR(100),
    status                VARCHAR(20)   NOT NULL CHECK (status IN ('RECEIVED','RECONCILED','EXCEPTION')),
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_settlement UNIQUE (provider, provider_settlement_id),
    CONSTRAINT ck_settlement_math CHECK (gross_amount - fee_amount - tax_amount = net_amount)
);

CREATE TABLE settlement_lines (
    id                  UUID PRIMARY KEY,
    batch_id            UUID          NOT NULL REFERENCES settlement_batches(id),
    provider_payment_id VARCHAR(100)  NOT NULL,
    donation_id         UUID,
    gross_amount        NUMERIC(14,2) NOT NULL,
    fee_amount          NUMERIC(14,2) NOT NULL,
    tax_amount          NUMERIC(14,2) NOT NULL,
    net_amount          NUMERIC(14,2) NOT NULL,
    matched             BOOLEAN       NOT NULL DEFAULT false
);
CREATE INDEX ix_sline_batch ON settlement_lines (batch_id);
CREATE UNIQUE INDEX uq_sline_payment ON settlement_lines (provider_payment_id);

CREATE TABLE reconciliation_runs (
    id           UUID PRIMARY KEY,
    ngo_id       UUID         NOT NULL,
    started_by   UUID,
    started_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    finished_at  TIMESTAMPTZ,
    batches      INT          NOT NULL DEFAULT 0,
    matched      INT          NOT NULL DEFAULT 0,
    exceptions   INT          NOT NULL DEFAULT 0
);

CREATE TABLE reconciliation_exceptions (
    id          UUID PRIMARY KEY,
    ngo_id      UUID         NOT NULL,
    run_id      UUID REFERENCES reconciliation_runs(id),
    kind        VARCHAR(40)  NOT NULL CHECK (kind IN ('UNMATCHED_SETTLEMENT','AMOUNT_MISMATCH','MISSING_SETTLEMENT','DUPLICATE_SETTLEMENT','FEE_MISMATCH','REFUND_MISMATCH','STATUS_MISMATCH')),
    donation_id UUID,
    reference   VARCHAR(120),
    expected    NUMERIC(14,2),
    actual      NUMERIC(14,2),
    status      VARCHAR(20)  NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','RESOLVED','IGNORED')),
    note        VARCHAR(500),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    resolved_by UUID,
    resolved_at TIMESTAMPTZ
);
CREATE INDEX ix_rexc_ngo ON reconciliation_exceptions (ngo_id, status);

-- Operational donation ledger (balanced double entry). Not a replacement for the NGO's statutory books.
CREATE TABLE ledger_accounts (
    id      UUID PRIMARY KEY,
    ngo_id  UUID        NOT NULL,
    code    VARCHAR(40) NOT NULL,
    name    VARCHAR(80) NOT NULL,
    kind    VARCHAR(10) NOT NULL CHECK (kind IN ('ASSET','LIABILITY','INCOME','EXPENSE')),
    CONSTRAINT uq_ledger_account UNIQUE (ngo_id, code)
);

CREATE TABLE journal_entries (
    id           UUID PRIMARY KEY,
    ngo_id       UUID         NOT NULL,
    entry_type   VARCHAR(40)  NOT NULL,
    reference_id VARCHAR(100) NOT NULL,
    memo         VARCHAR(200),
    reverses     UUID REFERENCES journal_entries(id),
    posted_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_journal_ref UNIQUE (ngo_id, entry_type, reference_id)
);
CREATE INDEX ix_journal_ngo ON journal_entries (ngo_id, posted_at DESC);

CREATE TABLE journal_lines (
    id         UUID PRIMARY KEY,
    entry_id   UUID          NOT NULL REFERENCES journal_entries(id),
    account_id UUID          NOT NULL REFERENCES ledger_accounts(id),
    debit      NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (debit >= 0),
    credit     NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (credit >= 0),
    CONSTRAINT ck_one_side CHECK ((debit = 0) <> (credit = 0))
);
CREATE INDEX ix_jline_entry ON journal_lines (entry_id);
CREATE INDEX ix_jline_account ON journal_lines (account_id);

-- Posted financial history is immutable: corrections are new reversing entries.
CREATE TRIGGER journal_entries_immutable BEFORE UPDATE OR DELETE ON journal_entries
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER journal_lines_immutable BEFORE UPDATE OR DELETE ON journal_lines
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER payment_events_immutable BEFORE UPDATE OR DELETE ON payment_events
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();

-- Every journal entry must balance; checked at commit time.
CREATE OR REPLACE FUNCTION check_entry_balanced() RETURNS trigger AS $$
DECLARE d NUMERIC; c NUMERIC;
BEGIN
    SELECT COALESCE(SUM(debit),0), COALESCE(SUM(credit),0) INTO d, c FROM journal_lines WHERE entry_id = NEW.entry_id;
    IF d <> c THEN
        RAISE EXCEPTION 'journal entry % is unbalanced (debit %, credit %)', NEW.entry_id, d, c;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER journal_balanced AFTER INSERT ON journal_lines
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_entry_balanced();
