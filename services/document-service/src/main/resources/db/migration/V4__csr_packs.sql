-- CSR compliance pack (Enterprise, owner decision 2026-11-02): the NGO picks documents from its vault and shares them
-- with a company through one expiring link. Nothing is copied: a pack points at vault documents, and when it is
-- shared the exact stored file (storage key + SHA-256) of each document is frozen, so the company always gets what
-- was sent even if the vault entry is edited later. The purge job keeps files that a live link still points to.

-- New vault category for CSR papers (CSR-1, board list, utilisation certificates, proposals ...).
ALTER TABLE documents DROP CONSTRAINT IF EXISTS documents_category_check;
ALTER TABLE documents ADD CONSTRAINT documents_category_check CHECK (category IN ('LEGAL_REGISTRATION','TAX_COMPLIANCE',
    'FINANCE','CAMPAIGN','PROJECT','AGREEMENT','MEETING','HR','PHOTOS_REPORTS','RECEIPT','CSR','OTHER'));

CREATE TABLE csr_packs (
    id              UUID PRIMARY KEY,
    ngo_id          UUID          NOT NULL,
    title           VARCHAR(120)  NOT NULL,
    recipient_name  VARCHAR(160)  NOT NULL,
    recipient_email VARCHAR(200),
    message         VARCHAR(1000),
    valid_days      INT           NOT NULL CHECK (valid_days BETWEEN 1 AND 90),
    status          VARCHAR(10)   NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','SHARED','REVOKED')),
    -- Random 128-bit code in the company's link (yogdaan.co.in/api/v1/public/csr/<code>); set when shared.
    code            VARCHAR(40)   UNIQUE,
    shared_at       TIMESTAMPTZ,
    shared_by       UUID,
    expires_at      TIMESTAMPTZ,
    revoked_at      TIMESTAMPTZ,
    revoked_by      UUID,
    created_by      UUID          NOT NULL,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version         BIGINT        NOT NULL DEFAULT 0,
    CHECK (status = 'DRAFT' OR (code IS NOT NULL AND shared_at IS NOT NULL AND expires_at IS NOT NULL))
);
CREATE INDEX ix_csr_packs_ngo ON csr_packs (ngo_id, created_at DESC);

-- Documents in a pack. kind = what the document proves (PAN, 80G, CSR-1 ...). The snapshot columns are filled when
-- the pack is shared and never change afterwards (trigger below).
CREATE TABLE csr_pack_items (
    id           UUID PRIMARY KEY,
    pack_id      UUID          NOT NULL REFERENCES csr_packs(id),
    document_id  UUID          NOT NULL REFERENCES documents(id),
    kind         VARCHAR(20)   NOT NULL CHECK (kind IN ('REGISTRATION','PAN','REG_12A','REG_80G','CSR1','AUDITED_ACCOUNTS',
                     'ANNUAL_REPORT','FCRA','BOARD_LIST','UTILISATION','PROPOSAL','OTHER')),
    position     INT           NOT NULL,
    title        VARCHAR(200),
    file_type    VARCHAR(10),
    content_type VARCHAR(120),
    size_bytes   BIGINT,
    sha256       VARCHAR(64),
    storage_key  VARCHAR(400),
    expires_on   DATE,
    frozen_at    TIMESTAMPTZ,
    CONSTRAINT uq_csr_pack_document UNIQUE (pack_id, document_id)
);
CREATE INDEX ix_csr_items_key ON csr_pack_items (storage_key) WHERE storage_key IS NOT NULL;

CREATE FUNCTION csr_item_frozen() RETURNS trigger AS $$
BEGIN
    IF OLD.frozen_at IS NOT NULL THEN
        RAISE EXCEPTION 'csr_pack_items: a shared document cannot be changed or removed';
    END IF;
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END $$ LANGUAGE plpgsql;
CREATE TRIGGER trg_csr_item_frozen BEFORE UPDATE OR DELETE ON csr_pack_items
    FOR EACH ROW EXECUTE FUNCTION csr_item_frozen();

-- Who opened the company page and downloaded what (append-only). No IP address is stored.
CREATE TABLE csr_pack_views (
    id         BIGSERIAL PRIMARY KEY,
    pack_id    UUID         NOT NULL REFERENCES csr_packs(id),
    item_id    UUID,
    action     VARCHAR(15)  NOT NULL CHECK (action IN ('OPENED','DOWNLOADED','DOWNLOADED_ALL')),
    client     VARCHAR(120),
    at         TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_csr_views_pack ON csr_pack_views (pack_id, at DESC);
CREATE FUNCTION csr_views_immutable() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'csr_pack_views is append-only';
END $$ LANGUAGE plpgsql;
CREATE TRIGGER trg_csr_views_immutable BEFORE UPDATE OR DELETE ON csr_pack_views
    FOR EACH ROW EXECUTE FUNCTION csr_views_immutable();
