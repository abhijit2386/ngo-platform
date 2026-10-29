-- Technical tables present in every service database (outbox, inbox, audit, idempotency).
CREATE TABLE outbox_events (
    id              UUID PRIMARY KEY,
    event_type      VARCHAR(120) NOT NULL,
    event_version   INT          NOT NULL CHECK (event_version > 0),
    tenant_id       UUID,
    resource_id     VARCHAR(100),
    correlation_id  VARCHAR(64),
    payload         JSONB        NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ,
    attempts        INT          NOT NULL DEFAULT 0,
    last_error      VARCHAR(500)
);
CREATE INDEX ix_outbox_unpublished ON outbox_events (created_at) WHERE published_at IS NULL;

CREATE TABLE inbox_processed_events (
    id           UUID PRIMARY KEY,
    consumer     VARCHAR(120) NOT NULL,
    event_id     UUID         NOT NULL,
    processed_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_inbox_consumer_event UNIQUE (consumer, event_id)
);

CREATE TABLE audit_events (
    id             UUID PRIMARY KEY,
    tenant_id      UUID,
    actor_id       UUID,
    action         VARCHAR(80)  NOT NULL,
    resource_type  VARCHAR(80)  NOT NULL,
    resource_id    VARCHAR(100),
    details        JSONB,
    correlation_id VARCHAR(64),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_audit_tenant_time ON audit_events (tenant_id, created_at DESC);
CREATE INDEX ix_audit_resource ON audit_events (resource_type, resource_id);

CREATE OR REPLACE FUNCTION forbid_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'table % is append-only', TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER audit_events_append_only BEFORE UPDATE OR DELETE ON audit_events
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();

CREATE TABLE idempotency_keys (
    id            UUID PRIMARY KEY,
    scope         VARCHAR(80)  NOT NULL,
    idem_key      VARCHAR(100) NOT NULL,
    request_hash  CHAR(64)     NOT NULL,
    response_body TEXT,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_idempotency UNIQUE (scope, idem_key)
);
