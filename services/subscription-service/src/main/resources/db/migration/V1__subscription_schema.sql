CREATE TABLE subscription_plans (
    code            VARCHAR(20) PRIMARY KEY,
    name            VARCHAR(60)   NOT NULL,
    monthly_price   NUMERIC(12,2) NOT NULL CHECK (monthly_price > 0),
    included_staff  INT           NOT NULL CHECK (included_staff >= 0),
    rank            INT           NOT NULL UNIQUE,
    accent_color    VARCHAR(7)    NOT NULL,
    active          BOOLEAN       NOT NULL DEFAULT true,
    staff_blocks_allowed BOOLEAN  NOT NULL DEFAULT false
);

CREATE TABLE plan_entitlements (
    plan_code VARCHAR(20)  NOT NULL REFERENCES subscription_plans(code),
    key       VARCHAR(60)  NOT NULL,
    value     VARCHAR(60)  NOT NULL,
    PRIMARY KEY (plan_code, key)
);

CREATE TABLE billing_settings (
    key   VARCHAR(60) PRIMARY KEY,
    value VARCHAR(60) NOT NULL
);

CREATE TABLE ngo_subscriptions (
    id                      UUID PRIMARY KEY,
    ngo_id                  UUID          NOT NULL UNIQUE,
    plan_code               VARCHAR(20)   NOT NULL REFERENCES subscription_plans(code),
    billing_cycle           VARCHAR(20)   NOT NULL CHECK (billing_cycle IN ('TRIAL','QUARTERLY','ANNUAL')),
    status                  VARCHAR(20)   NOT NULL CHECK (status IN ('TRIAL','ACTIVE','GRACE','SUSPENDED','CANCELLED','EXPIRED','ARCHIVED')),
    trial_used              BOOLEAN       NOT NULL DEFAULT false,
    current_period_start    TIMESTAMPTZ   NOT NULL,
    current_period_end      TIMESTAMPTZ   NOT NULL,
    grace_ends_at           TIMESTAMPTZ,
    contracted_period_price NUMERIC(12,2) NOT NULL DEFAULT 0 CHECK (contracted_period_price >= 0),
    contracted_block_price  NUMERIC(12,2) NOT NULL DEFAULT 0 CHECK (contracted_block_price >= 0),
    staff_blocks            INT           NOT NULL DEFAULT 0 CHECK (staff_blocks >= 0),
    scheduled_staff_blocks  INT           CHECK (scheduled_staff_blocks >= 0),
    scheduled_plan_code     VARCHAR(20)   REFERENCES subscription_plans(code),
    scheduled_cycle         VARCHAR(20)   CHECK (scheduled_cycle IN ('QUARTERLY','ANNUAL')),
    auto_renew              BOOLEAN       NOT NULL DEFAULT false,
    cancel_at_period_end    BOOLEAN       NOT NULL DEFAULT false,
    billing_contact_name    VARCHAR(120),
    billing_contact_email   VARCHAR(254),
    gstin                   VARCHAR(15)   CHECK (gstin IS NULL OR gstin ~ '^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$'),
    suspended_at            TIMESTAMPTZ,
    created_at              TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version                 BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT ck_period CHECK (current_period_end > current_period_start)
);
CREATE INDEX ix_sub_status_end ON ngo_subscriptions (status, current_period_end);

CREATE TABLE subscription_quotes (
    id                   UUID PRIMARY KEY,
    ngo_id               UUID          NOT NULL,
    kind                 VARCHAR(20)   NOT NULL CHECK (kind IN ('NEW_TERM','UPGRADE','ADD_BLOCKS','RENEWAL','REACTIVATION')),
    target_plan          VARCHAR(20)   NOT NULL REFERENCES subscription_plans(code),
    target_cycle         VARCHAR(20)   NOT NULL CHECK (target_cycle IN ('QUARTERLY','ANNUAL')),
    target_blocks        INT           NOT NULL DEFAULT 0 CHECK (target_blocks >= 0),
    amount               NUMERIC(12,2) NOT NULL CHECK (amount >= 0),
    breakdown            JSONB         NOT NULL,
    subscription_version BIGINT        NOT NULL,
    status               VARCHAR(20)   NOT NULL CHECK (status IN ('OPEN','PAID','EXPIRED','CANCELLED','NEEDS_REVIEW')),
    expires_at           TIMESTAMPTZ   NOT NULL,
    created_by           UUID          NOT NULL,
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version              BIGINT        NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uq_one_open_quote ON subscription_quotes (ngo_id) WHERE status = 'OPEN';

CREATE TABLE subscription_payments (
    id                  UUID PRIMARY KEY,
    ngo_id              UUID          NOT NULL,
    quote_id            UUID          NOT NULL REFERENCES subscription_quotes(id),
    provider            VARCHAR(30)   NOT NULL,
    provider_order_id   VARCHAR(100)  NOT NULL UNIQUE,
    provider_payment_id VARCHAR(100)  UNIQUE,
    amount              NUMERIC(12,2) NOT NULL CHECK (amount >= 0),
    paid_amount         NUMERIC(12,2),
    status              VARCHAR(20)   NOT NULL CHECK (status IN ('CREATED','PENDING','PAID','FAILED','CANCELLED','NEEDS_REVIEW','REFUNDED')),
    failure_reason      VARCHAR(200),
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version             BIGINT        NOT NULL DEFAULT 0
);
CREATE INDEX ix_subpay_ngo ON subscription_payments (ngo_id, created_at DESC);

CREATE SEQUENCE subscription_invoice_seq START 1;

CREATE TABLE subscription_invoices (
    id             UUID PRIMARY KEY,
    number         VARCHAR(30)   NOT NULL UNIQUE,
    ngo_id         UUID          NOT NULL,
    payment_id     UUID          NOT NULL UNIQUE REFERENCES subscription_payments(id),
    total          NUMERIC(12,2) NOT NULL,
    taxable_amount NUMERIC(12,2) NOT NULL,
    gst_amount     NUMERIC(12,2) NOT NULL,
    lines          JSONB         NOT NULL,
    bill_to_name   VARCHAR(200),
    bill_to_gstin  VARCHAR(15),
    period_start   TIMESTAMPTZ,
    period_end     TIMESTAMPTZ,
    status         VARCHAR(10)   NOT NULL DEFAULT 'ISSUED' CHECK (status IN ('ISSUED','VOID')),
    issued_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_invoice_sum CHECK (taxable_amount + gst_amount = total)
);
CREATE INDEX ix_invoice_ngo ON subscription_invoices (ngo_id, issued_at DESC);

CREATE TABLE subscription_changes (
    id           UUID PRIMARY KEY,
    ngo_id       UUID          NOT NULL,
    kind         VARCHAR(30)   NOT NULL,
    from_plan    VARCHAR(20),
    to_plan      VARCHAR(20),
    from_status  VARCHAR(20),
    to_status    VARCHAR(20),
    blocks       INT,
    amount       NUMERIC(12,2),
    actor_id     UUID,
    note         VARCHAR(300),
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX ix_changes_ngo ON subscription_changes (ngo_id, created_at DESC);

CREATE TABLE subscription_credits (
    id         UUID PRIMARY KEY,
    ngo_id     UUID          NOT NULL,
    amount     NUMERIC(12,2) NOT NULL CHECK (amount > 0),
    reason     VARCHAR(300)  NOT NULL,
    payment_id UUID REFERENCES subscription_payments(id),
    status     VARCHAR(20)   NOT NULL CHECK (status IN ('PENDING_REVIEW','AVAILABLE','APPLIED','REFUNDED','VOID')),
    created_at TIMESTAMPTZ   NOT NULL DEFAULT now(),
    resolved_at TIMESTAMPTZ
);

CREATE TABLE subscription_reminders (
    ngo_id     UUID        NOT NULL,
    period_end TIMESTAMPTZ NOT NULL,
    kind       VARCHAR(30) NOT NULL,
    sent_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (ngo_id, period_end, kind)
);

CREATE TABLE subscription_disputes (
    id          UUID PRIMARY KEY,
    ngo_id      UUID          NOT NULL,
    payment_id  UUID REFERENCES subscription_payments(id),
    kind        VARCHAR(20)   NOT NULL CHECK (kind IN ('REFUND_REQUEST','DISPUTE')),
    reason      VARCHAR(1000) NOT NULL,
    status      VARCHAR(20)   NOT NULL CHECK (status IN ('OPEN','APPROVED','REJECTED','CLOSED')),
    resolution  VARCHAR(1000),
    created_by  UUID          NOT NULL,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    resolved_at TIMESTAMPTZ
);

INSERT INTO subscription_plans (code, name, monthly_price, included_staff, rank, accent_color, staff_blocks_allowed) VALUES
 ('COMMUNITY','Community',199.00,1,1,'#087A4B',false),
 ('GROWTH','Growth',499.00,4,2,'#1976D2',false),
 ('PROFESSIONAL','Professional',1199.00,15,3,'#7437E8',false),
 ('ENTERPRISE','Enterprise',1699.00,30,4,'#E97716',true);

INSERT INTO plan_entitlements (plan_code, key, value) VALUES
 ('COMMUNITY','STAFF_MAX','1'),('GROWTH','STAFF_MAX','4'),('PROFESSIONAL','STAFF_MAX','15'),('ENTERPRISE','STAFF_MAX','30'),
 ('COMMUNITY','ADMIN_MAX','2'),('GROWTH','ADMIN_MAX','2'),('PROFESSIONAL','ADMIN_MAX','2'),('ENTERPRISE','ADMIN_MAX','2'),
 ('COMMUNITY','CAMPAIGN_ACTIVE_MAX','2'),('GROWTH','CAMPAIGN_ACTIVE_MAX','10'),('PROFESSIONAL','CAMPAIGN_ACTIVE_MAX','-1'),('ENTERPRISE','CAMPAIGN_ACTIVE_MAX','-1'),
 ('COMMUNITY','PROJECT_ACTIVE_MAX','2'),('GROWTH','PROJECT_ACTIVE_MAX','10'),('PROFESSIONAL','PROJECT_ACTIVE_MAX','-1'),('ENTERPRISE','PROJECT_ACTIVE_MAX','-1'),
 ('COMMUNITY','RECEIPT_BASIC_DESIGNER','false'),('GROWTH','RECEIPT_BASIC_DESIGNER','false'),('PROFESSIONAL','RECEIPT_BASIC_DESIGNER','true'),('ENTERPRISE','RECEIPT_BASIC_DESIGNER','true'),
 ('COMMUNITY','RECEIPT_ADVANCED_DESIGNER','false'),('GROWTH','RECEIPT_ADVANCED_DESIGNER','false'),('PROFESSIONAL','RECEIPT_ADVANCED_DESIGNER','false'),('ENTERPRISE','RECEIPT_ADVANCED_DESIGNER','true'),
 ('COMMUNITY','VOLUNTEER_FEATURE_LEVEL','BASIC'),('GROWTH','VOLUNTEER_FEATURE_LEVEL','STANDARD'),('PROFESSIONAL','VOLUNTEER_FEATURE_LEVEL','ADVANCED'),('ENTERPRISE','VOLUNTEER_FEATURE_LEVEL','ADVANCED'),
 ('COMMUNITY','REPORTING_LEVEL','BASIC'),('GROWTH','REPORTING_LEVEL','STANDARD'),('PROFESSIONAL','REPORTING_LEVEL','ADVANCED'),('ENTERPRISE','REPORTING_LEVEL','ADVANCED'),
 ('COMMUNITY','DONOR_SEGMENTATION_LEVEL','BASIC'),('GROWTH','DONOR_SEGMENTATION_LEVEL','STANDARD'),('PROFESSIONAL','DONOR_SEGMENTATION_LEVEL','ADVANCED'),('ENTERPRISE','DONOR_SEGMENTATION_LEVEL','ADVANCED'),
 ('COMMUNITY','CUSTOM_REPORTING','NONE'),('GROWTH','CUSTOM_REPORTING','NONE'),('PROFESSIONAL','CUSTOM_REPORTING','LIMITED'),('ENTERPRISE','CUSTOM_REPORTING','ADVANCED'),
 ('COMMUNITY','CUSTOM_INTEGRATION_ALLOWED','false'),('GROWTH','CUSTOM_INTEGRATION_ALLOWED','false'),('PROFESSIONAL','CUSTOM_INTEGRATION_ALLOWED','false'),('ENTERPRISE','CUSTOM_INTEGRATION_ALLOWED','false'),
 ('COMMUNITY','PRIORITY_SUPPORT','false'),('GROWTH','PRIORITY_SUPPORT','false'),('PROFESSIONAL','PRIORITY_SUPPORT','true'),('ENTERPRISE','PRIORITY_SUPPORT','true'),
 ('COMMUNITY','PLATFORM_HELPDESK','true'),('GROWTH','PLATFORM_HELPDESK','true'),('PROFESSIONAL','PLATFORM_HELPDESK','true'),('ENTERPRISE','PLATFORM_HELPDESK','true');

INSERT INTO billing_settings (key, value) VALUES
 ('trial_days','30'), ('grace_days','15'), ('annual_discount_percent','10'), ('staff_block_size','10'),
 ('staff_block_monthly_price','990.00'), ('gst_percent','18'), ('quote_ttl_minutes','30'),
 ('archive_after_days','365'), ('default_trial_plan','PROFESSIONAL');
