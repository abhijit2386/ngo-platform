-- Notification service (spec §20): templates, in-app inbox, per-channel deliveries with retries and
-- dead-lettering, communication preferences and opt-outs. Audit history uses the shared audit_events table.

CREATE TABLE notification_templates (
    id          UUID PRIMARY KEY,
    code        VARCHAR(80)  NOT NULL,
    channel     VARCHAR(20)  NOT NULL CHECK (channel IN ('EMAIL', 'IN_APP', 'PUSH')),
    language    VARCHAR(5)   NOT NULL CHECK (language IN ('en', 'hi', 'mr')),
    category    VARCHAR(30)  NOT NULL,
    subject     VARCHAR(200),
    body        TEXT         NOT NULL,
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    updated_by  UUID,
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version     BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_template UNIQUE (code, channel, language)
);

-- In-app inbox (NOTIF-001/002).
CREATE TABLE notifications (
    id              UUID PRIMARY KEY,
    user_id         UUID          NOT NULL,
    ngo_id          UUID,
    category        VARCHAR(30)   NOT NULL,
    template_code   VARCHAR(80)   NOT NULL,
    title           VARCHAR(200)  NOT NULL,
    body            VARCHAR(2000) NOT NULL,
    link            VARCHAR(300),
    source_event_id UUID,
    read_at         TIMESTAMPTZ,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_notification_event UNIQUE (source_event_id, user_id, template_code)
);
CREATE INDEX ix_notifications_user ON notifications (user_id, created_at DESC);
CREATE INDEX ix_notifications_unread ON notifications (user_id) WHERE read_at IS NULL;

-- Outbound messages per channel (email now; push/SMS/WhatsApp adapters later). Doubles as communication history.
CREATE TABLE notification_deliveries (
    id              UUID PRIMARY KEY,
    user_id         UUID,
    ngo_id          UUID,
    channel         VARCHAR(20)  NOT NULL CHECK (channel IN ('EMAIL', 'PUSH', 'SMS', 'WHATSAPP')),
    recipient       VARCHAR(254) NOT NULL,
    category        VARCHAR(30)  NOT NULL,
    template_code   VARCHAR(80)  NOT NULL,
    language        VARCHAR(5)   NOT NULL,
    subject         VARCHAR(200),
    body            TEXT         NOT NULL,
    status          VARCHAR(20)  NOT NULL CHECK (status IN ('PENDING', 'SENT', 'FAILED', 'DEAD', 'SUPPRESSED')),
    attempts        INT          NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_error      VARCHAR(500),
    resource_type   VARCHAR(40),
    resource_id     VARCHAR(100),
    source_event_id UUID,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    sent_at         TIMESTAMPTZ,
    version         BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_delivery_event UNIQUE (source_event_id, channel, recipient, template_code)
);
CREATE INDEX ix_deliveries_due ON notification_deliveries (next_attempt_at) WHERE status IN ('PENDING', 'FAILED');
CREATE INDEX ix_deliveries_user ON notification_deliveries (user_id, created_at DESC);
CREATE INDEX ix_deliveries_status ON notification_deliveries (status, created_at DESC);

-- NOTIF-003..005: per user and category. Absent row = defaults (everything on).
CREATE TABLE communication_preferences (
    user_id         UUID        NOT NULL,
    category        VARCHAR(30) NOT NULL,
    email_enabled   BOOLEAN     NOT NULL DEFAULT TRUE,
    in_app_enabled  BOOLEAN     NOT NULL DEFAULT TRUE,
    push_enabled    BOOLEAN     NOT NULL DEFAULT TRUE,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, category)
);

-- Global e-mail opt-out (e.g. "unsubscribe" from a non-member address). Mandatory account mail still goes out.
CREATE TABLE email_opt_outs (
    email      VARCHAR(254) PRIMARY KEY,
    reason     VARCHAR(200),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
