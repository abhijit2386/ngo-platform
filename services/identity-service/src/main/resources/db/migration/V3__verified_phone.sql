-- Owner rules 2026-10-29: a mobile number is stored only after SMS OTP verification; one verified number per
-- account and per number; NGO staff/admins must have one. Numbers entered before this rule were never verified,
-- so they are removed.
UPDATE users SET phone = NULL;
ALTER TABLE users ADD COLUMN phone_verified_at TIMESTAMPTZ;
ALTER TABLE users ADD CONSTRAINT ck_users_phone CHECK (phone IS NULL OR phone ~ '^[6-9][0-9]{9}$');
CREATE UNIQUE INDEX uq_users_verified_phone ON users (phone) WHERE phone IS NOT NULL;

-- One row per code sent. The code itself is never stored: only an HMAC of (user, number, code).
CREATE TABLE phone_verifications (
    id          UUID PRIMARY KEY,
    user_id     UUID        NOT NULL,
    phone       VARCHAR(10) NOT NULL CHECK (phone ~ '^[6-9][0-9]{9}$'),
    code_hash   CHAR(64)    NOT NULL,
    attempts    INT         NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    expires_at  TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_phone_verif_user ON phone_verifications (user_id, created_at DESC);
CREATE INDEX ix_phone_verif_phone ON phone_verifications (phone, created_at DESC);
