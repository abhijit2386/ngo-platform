-- CSR funding proposals (Enterprise: CSR_PROPOSALS, owner decision 2026-11-02).
-- A proposal is written from one campaign (its budget and progress are printed from the campaign's own accounts).
-- DRAFT: editable. SENT: the PDF exactly as sent is stored (and listed in the vault, category CSR) and the text is
-- frozen; follow-ups and notes go to the log. FUNDED / DECLINED / WITHDRAWN close it. Nothing is ever deleted.
CREATE TABLE csr_proposals (
    id                  UUID PRIMARY KEY,
    ngo_id              UUID          NOT NULL,
    campaign_id         UUID          NOT NULL REFERENCES campaigns(id),
    proposal_number     VARCHAR(30)   NOT NULL,
    fiscal_year         VARCHAR(7)    NOT NULL,
    title               VARCHAR(160)  NOT NULL,
    funder_name         VARCHAR(160)  NOT NULL,
    funder_contact      VARCHAR(120),
    funder_email        VARCHAR(160),
    schedule_vii        VARCHAR(30)   NOT NULL,
    ask_amount          NUMERIC(14,2) NOT NULL CHECK (ask_amount > 0),
    duration_months     INT           NOT NULL CHECK (duration_months BETWEEN 1 AND 60),
    beneficiaries       INT           CHECK (beneficiaries IS NULL OR beneficiaries >= 0),
    location            VARCHAR(160),
    need                TEXT          NOT NULL DEFAULT '',
    objectives          TEXT          NOT NULL DEFAULT '',
    activities          TEXT          NOT NULL DEFAULT '',
    outcomes            TEXT          NOT NULL DEFAULT '',
    monitoring          TEXT          NOT NULL DEFAULT '',
    contact_name        VARCHAR(120),
    contact_details     VARCHAR(200),
    -- Compliance pack (document-service) whose link is printed in the proposal; copied when attached.
    pack_id             UUID,
    pack_title          VARCHAR(160),
    pack_url            VARCHAR(300),
    pack_expires_at     TIMESTAMPTZ,
    status              VARCHAR(10)   NOT NULL DEFAULT 'DRAFT'
                            CHECK (status IN ('DRAFT','SENT','FUNDED','DECLINED','WITHDRAWN')),
    sent_on             DATE,
    sent_by             UUID,
    follow_up_on        DATE,
    pdf_key             VARCHAR(300),
    pdf_sha256          CHAR(64),
    funded_amount       NUMERIC(14,2) CHECK (funded_amount IS NULL OR funded_amount > 0),
    funded_on           DATE,
    funder_reference    VARCHAR(120),
    close_reason        VARCHAR(300),
    closed_by           UUID,
    closed_at           TIMESTAMPTZ,
    created_by          UUID          NOT NULL,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version             BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT uq_proposal_number UNIQUE (ngo_id, proposal_number),
    CHECK (status = 'DRAFT' OR status = 'WITHDRAWN' OR (sent_on IS NOT NULL AND pdf_key IS NOT NULL)),
    CHECK (status <> 'FUNDED' OR (funded_amount IS NOT NULL AND funded_on IS NOT NULL))
);
CREATE INDEX ix_proposals_ngo ON csr_proposals (ngo_id, created_at DESC);
CREATE INDEX ix_proposals_campaign ON csr_proposals (campaign_id);

CREATE TABLE proposal_counters (
    ngo_id      UUID       NOT NULL,
    fiscal_year VARCHAR(7) NOT NULL,
    next_value  INT        NOT NULL DEFAULT 1,
    PRIMARY KEY (ngo_id, fiscal_year)
);

-- What happened with the funder: created, sent, notes / calls / meetings, follow-ups, outcome. Append-only.
CREATE TABLE proposal_events (
    id          UUID PRIMARY KEY,
    proposal_id UUID         NOT NULL REFERENCES csr_proposals(id),
    kind        VARCHAR(12)  NOT NULL CHECK (kind IN ('CREATED','SENT','NOTE','FUNDED','DECLINED','WITHDRAWN','COPIED')),
    note        VARCHAR(1000),
    follow_up_on DATE,
    actor       UUID,
    at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_proposal_events ON proposal_events (proposal_id, at);

CREATE FUNCTION proposal_events_guard() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'proposal_events: the proposal log is append-only';
END $$ LANGUAGE plpgsql;
CREATE TRIGGER trg_proposal_events_guard BEFORE UPDATE OR DELETE ON proposal_events
    FOR EACH ROW EXECUTE FUNCTION proposal_events_guard();

-- A sent proposal is frozen (the PDF the funder has is what we keep); a closed one cannot reopen; never deleted.
CREATE FUNCTION proposal_guard() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'csr_proposals: proposals are never deleted (withdraw instead)';
    END IF;
    IF OLD.status <> 'DRAFT' AND (NEW.title IS DISTINCT FROM OLD.title OR NEW.funder_name IS DISTINCT FROM OLD.funder_name
            OR NEW.ask_amount IS DISTINCT FROM OLD.ask_amount OR NEW.need IS DISTINCT FROM OLD.need
            OR NEW.objectives IS DISTINCT FROM OLD.objectives OR NEW.activities IS DISTINCT FROM OLD.activities
            OR NEW.outcomes IS DISTINCT FROM OLD.outcomes OR NEW.monitoring IS DISTINCT FROM OLD.monitoring
            OR NEW.pdf_key IS DISTINCT FROM OLD.pdf_key OR NEW.proposal_number IS DISTINCT FROM OLD.proposal_number) THEN
        RAISE EXCEPTION 'csr_proposals: a sent proposal is frozen';
    END IF;
    IF OLD.status IN ('FUNDED','DECLINED','WITHDRAWN') AND NEW IS DISTINCT FROM OLD THEN
        RAISE EXCEPTION 'csr_proposals: a closed proposal cannot change';
    END IF;
    RETURN NEW;
END $$ LANGUAGE plpgsql;
CREATE TRIGGER trg_proposal_guard BEFORE UPDATE OR DELETE ON csr_proposals
    FOR EACH ROW EXECUTE FUNCTION proposal_guard();

-- A utilisation certificate can name the proposal that won the grant.
ALTER TABLE utilisation_certificates ADD COLUMN proposal_id UUID REFERENCES csr_proposals(id);
