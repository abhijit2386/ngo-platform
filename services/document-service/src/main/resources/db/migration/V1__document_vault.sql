-- Document vault (owner decision 2026-11-01): one register of every NGO file. Files live in the shared private
-- bucket under ngos/<ngo-id>/...; names, categories and tags live here, so search never touches storage.
-- Other services (registration documents, expense bills, receipts) register their files by event (source <> UPLOAD).
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE documents (
    id                  UUID PRIMARY KEY,
    ngo_id              UUID          NOT NULL,
    title               VARCHAR(200)  NOT NULL,
    description         VARCHAR(1000),
    category            VARCHAR(30)   NOT NULL CHECK (category IN ('LEGAL_REGISTRATION','TAX_COMPLIANCE','FINANCE',
                            'CAMPAIGN','PROJECT','AGREEMENT','MEETING','HR','PHOTOS_REPORTS','RECEIPT','OTHER')),
    tags                TEXT[]        NOT NULL DEFAULT '{}',
    file_type           VARCHAR(10)   NOT NULL CHECK (file_type IN ('PDF','IMAGE','WORD','EXCEL','SLIDES','OTHER')),
    content_type        VARCHAR(120)  NOT NULL,
    size_bytes          BIGINT        NOT NULL CHECK (size_bytes >= 0),
    sha256              VARCHAR(64),
    original_name       VARCHAR(200),
    storage_key         VARCHAR(400)  NOT NULL,
    source              VARCHAR(20)   NOT NULL CHECK (source IN ('UPLOAD','REGISTRATION','EXPENSE_BILL','RECEIPT','MEETING_MINUTES')),
    source_ref          VARCHAR(120),
    -- TEAM: every member (who also holds required_permission); RESTRICTED: the uploader and administrators.
    visibility          VARCHAR(12)   NOT NULL CHECK (visibility IN ('TEAM','RESTRICTED')),
    required_permission VARCHAR(40),
    link_type           VARCHAR(20)   CHECK (link_type IN ('CAMPAIGN','EXPENSE','PROJECT','MEETING','NGO')),
    link_id             UUID,
    link_parent_id      UUID,
    link_label          VARCHAR(200),
    document_date       DATE,
    expires_on          DATE,
    uploaded_by         UUID,
    updated_by          UUID,
    status              VARCHAR(10)   NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','DELETED','PURGED')),
    deleted_by          UUID,
    deleted_at          TIMESTAMPTZ,
    purge_after         DATE,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version             BIGINT        NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uq_document_source ON documents (source, source_ref) WHERE source_ref IS NOT NULL;
CREATE INDEX ix_documents_ngo ON documents (ngo_id, status, created_at DESC);
CREATE INDEX ix_documents_category ON documents (ngo_id, category, status);
CREATE INDEX ix_documents_link ON documents (ngo_id, link_type, link_id);
CREATE INDEX ix_documents_tags ON documents USING gin (tags);
CREATE INDEX ix_documents_title_trgm ON documents USING gin (lower(title) gin_trgm_ops);
CREATE INDEX ix_documents_purge ON documents (status, purge_after) WHERE status = 'DELETED';

-- Who opened, downloaded, changed or deleted what (append-only).
CREATE TABLE document_activity (
    id          BIGSERIAL PRIMARY KEY,
    ngo_id      UUID         NOT NULL,
    document_id UUID         NOT NULL REFERENCES documents(id),
    user_id     UUID,
    action      VARCHAR(20)  NOT NULL CHECK (action IN ('UPLOADED','REGISTERED','VIEWED','LINK_CREATED','DOWNLOADED',
                    'UPDATED','DELETED','RESTORED','PURGED','REMOVED_AT_SOURCE')),
    at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_document_activity ON document_activity (document_id, at DESC);

CREATE FUNCTION document_activity_immutable() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'document_activity is append-only';
END $$ LANGUAGE plpgsql;
CREATE TRIGGER trg_document_activity_immutable BEFORE UPDATE OR DELETE ON document_activity
    FOR EACH ROW EXECUTE FUNCTION document_activity_immutable();

-- Bytes per NGO for the plan quota (object storage has no per-folder limits).
CREATE TABLE storage_usage (
    ngo_id     UUID PRIMARY KEY,
    bytes      BIGINT      NOT NULL DEFAULT 0 CHECK (bytes >= 0),
    files      INT         NOT NULL DEFAULT 0 CHECK (files >= 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One-time listing of files that existed before the vault (per source service).
CREATE TABLE backfill_state (
    source  VARCHAR(30) PRIMARY KEY,
    done_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    files   INT         NOT NULL
);
