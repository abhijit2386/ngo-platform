CREATE TABLE users (
    id                 UUID PRIMARY KEY,             -- Keycloak subject
    email              VARCHAR(254) NOT NULL,
    email_verified     BOOLEAN      NOT NULL DEFAULT false,
    display_name       VARCHAR(120),
    phone              VARCHAR(15),
    preferred_language VARCHAR(5)   NOT NULL DEFAULT 'en' CHECK (preferred_language IN ('en','hi','mr')),
    status             VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','DELETION_REQUESTED','DISABLED')),
    deletion_requested_at TIMESTAMPTZ,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version            BIGINT       NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uq_users_email ON users (lower(email));

CREATE TABLE user_identities (
    id         UUID PRIMARY KEY,
    user_id    UUID         NOT NULL REFERENCES users(id),
    provider   VARCHAR(40)  NOT NULL,
    subject    VARCHAR(255) NOT NULL,
    linked_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_identity UNIQUE (provider, subject)
);

CREATE TABLE roles (
    code     VARCHAR(40) PRIMARY KEY,
    name     VARCHAR(80) NOT NULL,
    is_admin BOOLEAN     NOT NULL DEFAULT false
);

CREATE TABLE permissions (
    code        VARCHAR(60) PRIMARY KEY,
    description VARCHAR(200) NOT NULL
);

CREATE TABLE role_permissions (
    role_code       VARCHAR(40) NOT NULL REFERENCES roles(code),
    permission_code VARCHAR(60) NOT NULL REFERENCES permissions(code),
    PRIMARY KEY (role_code, permission_code)
);

-- One row per NGO tenant known to identity; used as the lock row for seat accounting.
CREATE TABLE tenants (
    ngo_id     UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE ngo_memberships (
    id         UUID PRIMARY KEY,
    ngo_id     UUID        NOT NULL REFERENCES tenants(ngo_id),
    user_id    UUID        NOT NULL REFERENCES users(id),
    role_code  VARCHAR(40) NOT NULL REFERENCES roles(code),
    status     VARCHAR(20) NOT NULL CHECK (status IN ('ACTIVE','SUSPENDED','DEACTIVATED','REMOVED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version    BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT uq_membership UNIQUE (ngo_id, user_id)
);
CREATE INDEX ix_membership_user ON ngo_memberships (user_id);
-- Exactly one primary admin per NGO among non-removed members.
CREATE UNIQUE INDEX uq_primary_admin ON ngo_memberships (ngo_id)
    WHERE role_code = 'PRIMARY_ADMIN' AND status <> 'REMOVED';

CREATE TABLE membership_permission_overrides (
    membership_id   UUID        NOT NULL REFERENCES ngo_memberships(id),
    permission_code VARCHAR(60) NOT NULL REFERENCES permissions(code),
    granted         BOOLEAN     NOT NULL,
    PRIMARY KEY (membership_id, permission_code)
);

CREATE TABLE staff_invitations (
    id          UUID PRIMARY KEY,
    ngo_id      UUID         NOT NULL REFERENCES tenants(ngo_id),
    email       VARCHAR(254) NOT NULL,
    role_code   VARCHAR(40)  NOT NULL REFERENCES roles(code),
    status      VARCHAR(20)  NOT NULL CHECK (status IN ('PENDING_ACCEPTANCE','ACCEPTED','CANCELLED','EXPIRED')),
    invited_by  UUID,
    expires_at  TIMESTAMPTZ  NOT NULL,
    accepted_by UUID,
    accepted_at TIMESTAMPTZ,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version     BIGINT       NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uq_pending_invite ON staff_invitations (ngo_id, lower(email)) WHERE status = 'PENDING_ACCEPTANCE';
CREATE INDEX ix_invite_email ON staff_invitations (lower(email), status);

CREATE TABLE account_deletion_requests (
    id           UUID PRIMARY KEY,
    user_id      UUID        NOT NULL REFERENCES users(id),
    reason       VARCHAR(500),
    status       VARCHAR(20) NOT NULL CHECK (status IN ('REQUESTED','CANCELLED','COMPLETED','REJECTED')),
    requested_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    decided_at   TIMESTAMPTZ
);

INSERT INTO roles (code, name, is_admin) VALUES
 ('PRIMARY_ADMIN','Primary administrator',true),
 ('SECONDARY_ADMIN','Secondary administrator',true),
 ('STAFF','Staff',false),
 ('FINANCE','Finance staff',false),
 ('CAMPAIGN_MANAGER','Campaign manager',false),
 ('VOLUNTEER_COORDINATOR','Volunteer coordinator',false),
 ('VOLUNTEER','Volunteer',false);
