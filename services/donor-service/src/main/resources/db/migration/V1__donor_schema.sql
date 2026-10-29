-- NGO-scoped donor records. A donor supporting several NGOs has one record per NGO; NGOs never see each other's.
CREATE TABLE donors (
    id               UUID PRIMARY KEY,
    ngo_id           UUID          NOT NULL,
    donor_user_id    UUID,                          -- linked platform donor account (identity linking)
    type             VARCHAR(20)   NOT NULL CHECK (type IN ('INDIVIDUAL','ORGANIZATION')),
    name             VARCHAR(160)  NOT NULL,
    email            VARCHAR(254),
    phone            VARCHAR(15),
    pan              VARCHAR(10)   CHECK (pan IS NULL OR pan ~ '^[A-Z]{5}[0-9]{4}[A-Z]$'),
    address_line     VARCHAR(250),
    city             VARCHAR(80),
    state            VARCHAR(40),
    pin_code         CHAR(6)       CHECK (pin_code IS NULL OR pin_code ~ '^[1-9][0-9]{5}$'),
    contact_person   VARCHAR(120),
    tags             JSONB         NOT NULL DEFAULT '[]',
    notes            VARCHAR(1000),
    source           VARCHAR(20)   NOT NULL CHECK (source IN ('MANUAL','ONLINE','IMPORT','GUEST')),
    communication_opt_in BOOLEAN   NOT NULL DEFAULT true,
    total_donated    NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (total_donated >= 0),
    donation_count   INT           NOT NULL DEFAULT 0 CHECK (donation_count >= 0),
    first_donation_at TIMESTAMPTZ,
    last_donation_at TIMESTAMPTZ,
    status           VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','MERGED','ERASED')),
    merged_into      UUID REFERENCES donors(id),
    created_by       UUID,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version          BIGINT        NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uq_donor_email ON donors (ngo_id, lower(email)) WHERE status = 'ACTIVE' AND email IS NOT NULL;
CREATE UNIQUE INDEX uq_donor_phone ON donors (ngo_id, phone) WHERE status = 'ACTIVE' AND phone IS NOT NULL;
CREATE INDEX ix_donor_ngo_name ON donors (ngo_id, lower(name));
CREATE INDEX ix_donor_ngo_last ON donors (ngo_id, last_donation_at DESC);
CREATE INDEX ix_donor_user ON donors (donor_user_id);

-- Donation facts applied to totals (idempotent: one row per donation).
CREATE TABLE donor_donation_facts (
    donation_id UUID PRIMARY KEY,
    donor_id    UUID          NOT NULL REFERENCES donors(id),
    ngo_id      UUID          NOT NULL,
    amount      NUMERIC(14,2) NOT NULL,
    refunded    NUMERIC(14,2) NOT NULL DEFAULT 0,
    campaign_id UUID,
    paid_at     TIMESTAMPTZ   NOT NULL
);
CREATE INDEX ix_fact_donor ON donor_donation_facts (donor_id, paid_at DESC);

-- Platform donor accounts (donor app).
CREATE TABLE donor_profiles (
    user_id        UUID PRIMARY KEY,
    display_name   VARCHAR(120),
    phone          VARCHAR(15),
    pan            VARCHAR(10) CHECK (pan IS NULL OR pan ~ '^[A-Z]{5}[0-9]{4}[A-Z]$'),
    city           VARCHAR(80),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE donor_preferences (
    user_id          UUID PRIMARY KEY,
    email_receipts   BOOLEAN NOT NULL DEFAULT true,
    campaign_updates BOOLEAN NOT NULL DEFAULT true,
    marketing        BOOLEAN NOT NULL DEFAULT false,
    push_enabled     BOOLEAN NOT NULL DEFAULT true,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE donor_consents (
    id          UUID PRIMARY KEY,
    user_id     UUID        NOT NULL,
    purpose     VARCHAR(40) NOT NULL,
    granted     BOOLEAN     NOT NULL,
    notice_version VARCHAR(20) NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE donor_follows (
    user_id    UUID        NOT NULL,
    ngo_id     UUID        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, ngo_id)
);

CREATE TABLE saved_campaigns (
    user_id     UUID        NOT NULL,
    campaign_id UUID        NOT NULL,
    ngo_id      UUID        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, campaign_id)
);

CREATE TABLE privacy_requests (
    id          UUID PRIMARY KEY,
    user_id     UUID,
    ngo_id      UUID,
    donor_id    UUID REFERENCES donors(id),
    type        VARCHAR(20)  NOT NULL CHECK (type IN ('ACCESS','CORRECTION','ERASURE','GRIEVANCE','OPT_OUT')),
    details     VARCHAR(2000),
    status      VARCHAR(20)  NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','IN_PROGRESS','COMPLETED','REJECTED')),
    resolution  VARCHAR(2000),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    resolved_at TIMESTAMPTZ
);
CREATE INDEX ix_privacy_ngo ON privacy_requests (ngo_id, status);

CREATE TABLE donor_segments (
    id         UUID PRIMARY KEY,
    ngo_id     UUID         NOT NULL,
    name       VARCHAR(80)  NOT NULL,
    criteria   JSONB        NOT NULL,
    created_by UUID         NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_segment_name UNIQUE (ngo_id, name)
);

CREATE TABLE donor_imports (
    id          UUID PRIMARY KEY,
    ngo_id      UUID        NOT NULL,
    status      VARCHAR(20) NOT NULL CHECK (status IN ('PREVIEWED','COMMITTED','DISCARDED')),
    rows_total  INT         NOT NULL,
    rows_valid  INT         NOT NULL,
    rows_duplicate INT      NOT NULL,
    rows_invalid INT        NOT NULL,
    payload     JSONB       NOT NULL,
    created_by  UUID        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
