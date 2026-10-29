CREATE TABLE campaigns (
    id             UUID PRIMARY KEY,
    ngo_id         UUID          NOT NULL,
    title          VARCHAR(120)  NOT NULL,
    slug           VARCHAR(140)  NOT NULL,
    summary        VARCHAR(300)  NOT NULL,
    story          VARCHAR(5000),
    cause          VARCHAR(40),
    goal_amount    NUMERIC(14,2) NOT NULL CHECK (goal_amount > 0),
    raised_amount  NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (raised_amount >= 0),
    donation_count INT           NOT NULL DEFAULT 0,
    start_date     DATE          NOT NULL,
    end_date       DATE,
    status         VARCHAR(20)   NOT NULL CHECK (status IN ('DRAFT','PENDING_APPROVAL','PUBLISHED','CLOSED','ARCHIVED')),
    image_key      VARCHAR(300),
    project_id     UUID,
    created_by     UUID          NOT NULL,
    approved_by    UUID,
    published_at   TIMESTAMPTZ,
    closed_at      TIMESTAMPTZ,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version        BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT ck_campaign_dates CHECK (end_date IS NULL OR end_date >= start_date),
    CONSTRAINT uq_campaign_slug UNIQUE (ngo_id, slug)
);
CREATE INDEX ix_campaign_ngo ON campaigns (ngo_id, status);
CREATE INDEX ix_campaign_public ON campaigns (status, published_at DESC);

CREATE TABLE campaign_donations (
    donation_id UUID PRIMARY KEY,
    campaign_id UUID          NOT NULL REFERENCES campaigns(id),
    amount      NUMERIC(14,2) NOT NULL,
    refunded    NUMERIC(14,2) NOT NULL DEFAULT 0
);

CREATE TABLE projects (
    id            UUID PRIMARY KEY,
    ngo_id        UUID          NOT NULL,
    name          VARCHAR(120)  NOT NULL,
    description   VARCHAR(3000),
    location      VARCHAR(120),
    start_date    DATE          NOT NULL,
    end_date      DATE,
    budget_amount NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (budget_amount >= 0),
    spent_amount  NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (spent_amount >= 0),
    funds_raised  NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (funds_raised >= 0),
    progress      INT           NOT NULL DEFAULT 0 CHECK (progress BETWEEN 0 AND 100),
    status        VARCHAR(20)   NOT NULL CHECK (status IN ('PLANNED','ACTIVE','ON_HOLD','COMPLETED','CLOSED')),
    created_by    UUID          NOT NULL,
    closed_at     TIMESTAMPTZ,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version       BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT ck_project_dates CHECK (end_date IS NULL OR end_date >= start_date)
);
CREATE INDEX ix_project_ngo ON projects (ngo_id, status);

CREATE TABLE project_milestones (
    id          UUID PRIMARY KEY,
    project_id  UUID         NOT NULL REFERENCES projects(id),
    title       VARCHAR(120) NOT NULL,
    due_date    DATE,
    status      VARCHAR(20)  NOT NULL CHECK (status IN ('PENDING','IN_PROGRESS','DONE','MISSED')),
    weight      INT          NOT NULL DEFAULT 1 CHECK (weight BETWEEN 1 AND 100),
    completed_at TIMESTAMPTZ,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE project_assignments (
    project_id  UUID        NOT NULL REFERENCES projects(id),
    user_id     UUID        NOT NULL,
    role        VARCHAR(40) NOT NULL,
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (project_id, user_id)
);

CREATE TABLE project_budget_lines (
    id         UUID PRIMARY KEY,
    project_id UUID          NOT NULL REFERENCES projects(id),
    category   VARCHAR(60)   NOT NULL,
    amount     NUMERIC(14,2) NOT NULL CHECK (amount >= 0),
    CONSTRAINT uq_budget_line UNIQUE (project_id, category)
);

CREATE TABLE project_activities (
    id         UUID PRIMARY KEY,
    project_id UUID          NOT NULL REFERENCES projects(id),
    kind       VARCHAR(30)   NOT NULL,
    text       VARCHAR(1000) NOT NULL,
    actor_id   UUID,
    created_at TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX ix_pact_project ON project_activities (project_id, created_at DESC);

CREATE TABLE project_documents (
    id           UUID PRIMARY KEY,
    project_id   UUID         NOT NULL REFERENCES projects(id),
    object_key   VARCHAR(300) NOT NULL,
    file_name    VARCHAR(120) NOT NULL,
    content_type VARCHAR(60)  NOT NULL,
    size_bytes   BIGINT       NOT NULL,
    uploaded_by  UUID         NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Project expenditure as reported by operations-service (idempotent per expense).
CREATE TABLE project_expense_facts (
    expense_id UUID PRIMARY KEY,
    project_id UUID          NOT NULL REFERENCES projects(id),
    amount     NUMERIC(14,2) NOT NULL
);
