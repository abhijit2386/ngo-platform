CREATE TABLE receipt_sequences (
    ngo_id      UUID        NOT NULL,
    fiscal_year VARCHAR(7)  NOT NULL,           -- e.g. 2026-27
    next_value  BIGINT      NOT NULL CHECK (next_value > 0),
    PRIMARY KEY (ngo_id, fiscal_year)
);

CREATE TABLE receipt_templates (
    id              UUID PRIMARY KEY,
    ngo_id          UUID         NOT NULL,
    name            VARCHAR(80)  NOT NULL,
    kind            VARCHAR(10)  NOT NULL CHECK (kind IN ('BASIC','ADVANCED')),
    status          VARCHAR(20)  NOT NULL CHECK (status IN ('DRAFT','PENDING_APPROVAL','PUBLISHED','ARCHIVED')),
    is_active       BOOLEAN      NOT NULL DEFAULT false,
    current_version INT          NOT NULL DEFAULT 1,
    published_version INT,
    created_by      UUID         NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version         BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_template_name UNIQUE (ngo_id, name)
);
CREATE UNIQUE INDEX uq_one_active_template ON receipt_templates (ngo_id) WHERE is_active;

CREATE TABLE receipt_template_versions (
    id             UUID PRIMARY KEY,
    template_id    UUID         NOT NULL REFERENCES receipt_templates(id),
    version_no     INT          NOT NULL CHECK (version_no > 0),
    definition     JSONB        NOT NULL,
    background_key VARCHAR(300),
    created_by     UUID         NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_template_version UNIQUE (template_id, version_no)
);

CREATE TABLE receipt_template_approvals (
    id          UUID PRIMARY KEY,
    template_id UUID         NOT NULL REFERENCES receipt_templates(id),
    version_no  INT          NOT NULL,
    requested_by UUID        NOT NULL,
    decided_by  UUID,
    decision    VARCHAR(10)  CHECK (decision IN ('APPROVED','REJECTED')),
    comment     VARCHAR(500),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    decided_at  TIMESTAMPTZ,
    CONSTRAINT ck_template_maker_checker CHECK (decided_by IS NULL OR decided_by <> requested_by)
);

CREATE TABLE donation_receipts (
    id              UUID PRIMARY KEY,
    ngo_id          UUID          NOT NULL,
    donation_id     UUID          NOT NULL,
    donor_id        UUID          NOT NULL,
    donor_user_id   UUID,
    receipt_number  VARCHAR(40),
    amount          NUMERIC(14,2) NOT NULL CHECK (amount > 0),
    status          VARCHAR(20)   NOT NULL CHECK (status IN ('PENDING','GENERATING','GENERATED','DELIVERY_PENDING','DELIVERED','GENERATION_FAILED','VOIDED','REISSUED')),
    template_id     UUID,
    template_version INT,
    tax_80g         BOOLEAN       NOT NULL DEFAULT false,
    pdf_key         VARCHAR(300),
    pdf_sha256      CHAR(64),
    attempts        INT           NOT NULL DEFAULT 0,
    last_error      VARCHAR(300),
    next_attempt_at TIMESTAMPTZ,
    reissue_of      UUID REFERENCES donation_receipts(id),
    void_reason     VARCHAR(300),
    issued_at       TIMESTAMPTZ,
    delivered_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version         BIGINT        NOT NULL DEFAULT 0
);
-- One live receipt per donation; voided/reissued history is kept.
CREATE UNIQUE INDEX uq_live_receipt ON donation_receipts (donation_id) WHERE status NOT IN ('VOIDED','REISSUED');
CREATE UNIQUE INDEX uq_receipt_number ON donation_receipts (ngo_id, receipt_number) WHERE receipt_number IS NOT NULL;
CREATE INDEX ix_receipt_ngo ON donation_receipts (ngo_id, created_at DESC);
CREATE INDEX ix_receipt_user ON donation_receipts (donor_user_id, created_at DESC);
CREATE INDEX ix_receipt_retry ON donation_receipts (status, next_attempt_at);

CREATE TABLE receipt_events (
    id          UUID PRIMARY KEY,
    receipt_id  UUID         NOT NULL REFERENCES donation_receipts(id),
    event       VARCHAR(40)  NOT NULL,
    detail      VARCHAR(300),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_revent_receipt ON receipt_events (receipt_id, created_at);
CREATE TRIGGER receipt_events_append_only BEFORE UPDATE OR DELETE ON receipt_events
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
