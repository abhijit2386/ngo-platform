CREATE TABLE ngos (
    id                        UUID PRIMARY KEY,
    legal_name                VARCHAR(200) NOT NULL,
    display_name              VARCHAR(120) NOT NULL,
    registration_type         VARCHAR(30)  NOT NULL CHECK (registration_type IN ('PUBLIC_TRUST','SOCIETY','SECTION_8_COMPANY','OTHER')),
    registration_number       VARCHAR(60),
    registration_number_norm  VARCHAR(60),
    registration_date         DATE,
    registration_state        VARCHAR(40),
    darpan_id                 VARCHAR(20),
    pan                       VARCHAR(10),
    description               VARCHAR(2000),
    areas_of_activity         JSONB        NOT NULL DEFAULT '[]',
    official_email            VARCHAR(254),
    official_phone            VARCHAR(15),
    website                   VARCHAR(200),
    logo_key                  VARCHAR(300),
    brand_color               VARCHAR(7),
    tax_80g_number            VARCHAR(60),
    tax_80g_valid_until       DATE,
    tax_80g_verified          BOOLEAN      NOT NULL DEFAULT false,
    account_status            VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE' CHECK (account_status IN ('ACTIVE','SUSPENDED','CLOSURE_REQUESTED','CLOSED')),
    registration_status       VARCHAR(20)  NOT NULL DEFAULT 'DRAFT' CHECK (registration_status IN ('DRAFT','SUBMITTED')),
    verification_status       VARCHAR(20)  NOT NULL DEFAULT 'NOT_SUBMITTED' CHECK (verification_status IN ('NOT_SUBMITTED','PENDING','INFO_REQUESTED','APPROVED','REJECTED')),
    public_profile_status     VARCHAR(20)  NOT NULL DEFAULT 'HIDDEN' CHECK (public_profile_status IN ('HIDDEN','PUBLISHED')),
    compliance_hold           BOOLEAN      NOT NULL DEFAULT false,
    payment_onboarding_status VARCHAR(20)  NOT NULL DEFAULT 'NOT_STARTED' CHECK (payment_onboarding_status IN ('NOT_STARTED','PENDING','ACTIVE','REJECTED')),
    risk_flags                JSONB        NOT NULL DEFAULT '[]',
    verified_at               TIMESTAMPTZ,
    reverify_due_at           TIMESTAMPTZ,
    submitted_at              TIMESTAMPTZ,
    created_by                UUID         NOT NULL,
    created_at                TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at                TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version                   BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ck_pan CHECK (pan IS NULL OR pan ~ '^[A-Z]{5}[0-9]{4}[A-Z]$'),
    CONSTRAINT ck_darpan CHECK (darpan_id IS NULL OR darpan_id ~ '^[A-Z]{2}/[0-9]{4}/[0-9]{7}$'),
    CONSTRAINT ck_color CHECK (brand_color IS NULL OR brand_color ~ '^#[0-9A-Fa-f]{6}$')
);
-- A registration number may only be held by one live (non-rejected, submitted) NGO per state.
CREATE UNIQUE INDEX uq_ngo_registration ON ngos (registration_state, registration_number_norm)
    WHERE registration_status = 'SUBMITTED' AND verification_status <> 'REJECTED' AND account_status <> 'CLOSED';
CREATE INDEX ix_ngo_pan ON ngos (pan);
CREATE INDEX ix_ngo_public ON ngos (public_profile_status, account_status);
CREATE INDEX ix_ngo_verification ON ngos (verification_status, submitted_at);
CREATE INDEX ix_ngo_creator ON ngos (created_by);

CREATE TABLE ngo_addresses (
    ngo_id     UUID PRIMARY KEY REFERENCES ngos(id),
    line1      VARCHAR(200) NOT NULL,
    line2      VARCHAR(200),
    city       VARCHAR(80)  NOT NULL,
    district   VARCHAR(80)  NOT NULL,
    state      VARCHAR(40)  NOT NULL,
    pin_code   CHAR(6)      NOT NULL CHECK (pin_code ~ '^[1-9][0-9]{5}$')
);
CREATE INDEX ix_addr_city ON ngo_addresses (lower(city));

CREATE TABLE ngo_contacts (
    id          UUID PRIMARY KEY,
    ngo_id      UUID         NOT NULL REFERENCES ngos(id),
    kind        VARCHAR(20)  NOT NULL CHECK (kind IN ('PRIMARY_ADMIN','SECONDARY_ADMIN','SIGNATORY')),
    name        VARCHAR(120) NOT NULL,
    email       VARCHAR(254),
    phone       VARCHAR(15),
    designation VARCHAR(80),
    CONSTRAINT uq_contact_kind UNIQUE (ngo_id, kind)
);

CREATE TABLE ngo_documents (
    id           UUID PRIMARY KEY,
    ngo_id       UUID         NOT NULL REFERENCES ngos(id),
    doc_type     VARCHAR(40)  NOT NULL CHECK (doc_type IN ('REGISTRATION_CERTIFICATE','PAN_CARD','TRUST_DEED_OR_MOA','CERTIFICATE_12A','CERTIFICATE_80G','FCRA','ADDRESS_PROOF','SIGNATORY_ID','LOGO','OTHER')),
    object_key   VARCHAR(300) NOT NULL,
    file_name    VARCHAR(120) NOT NULL,
    content_type VARCHAR(60)  NOT NULL,
    size_bytes   BIGINT       NOT NULL CHECK (size_bytes > 0),
    sha256       CHAR(64)     NOT NULL,
    status       VARCHAR(20)  NOT NULL DEFAULT 'UPLOADED' CHECK (status IN ('UPLOADED','ACCEPTED','REJECTED','SUPERSEDED')),
    expires_on   DATE,
    review_note  VARCHAR(500),
    uploaded_by  UUID         NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_docs_ngo ON ngo_documents (ngo_id, doc_type);

CREATE TABLE ngo_verifications (
    id             UUID PRIMARY KEY,
    ngo_id         UUID         NOT NULL REFERENCES ngos(id),
    action         VARCHAR(30)  NOT NULL,
    actor_id       UUID,
    internal_note  VARCHAR(2000),
    message_to_ngo VARCHAR(2000),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_verif_ngo ON ngo_verifications (ngo_id, created_at);

CREATE TABLE ngo_change_requests (
    id           UUID PRIMARY KEY,
    ngo_id       UUID         NOT NULL REFERENCES ngos(id),
    field        VARCHAR(40)  NOT NULL CHECK (field IN ('LEGAL_NAME','REGISTRATION_NUMBER','PAN','REGISTRATION_TYPE')),
    old_value    VARCHAR(200),
    new_value    VARCHAR(200) NOT NULL,
    reason       VARCHAR(500),
    status       VARCHAR(20)  NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','APPROVED','REJECTED','CANCELLED')),
    requested_by UUID         NOT NULL,
    decided_by   UUID,
    decided_at   TIMESTAMPTZ,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_pending_change ON ngo_change_requests (ngo_id, field) WHERE status = 'PENDING';

CREATE TABLE app_config (
    key   VARCHAR(60) PRIMARY KEY,
    value VARCHAR(500) NOT NULL
);
INSERT INTO app_config VALUES ('maintenance_mode','false'), ('min_app_build','1'), ('support_email','support@example.org');
