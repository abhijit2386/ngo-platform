-- Profile completion (owner decisions 2026-10-29): adults only (18+), optional gender, optional picture
-- (own photo or built-in avatar), date of birth fixed once saved.
ALTER TABLE users ADD COLUMN birth_date DATE CHECK (birth_date IS NULL OR birth_date >= DATE '1900-01-01');
ALTER TABLE users ADD COLUMN gender VARCHAR(20) NOT NULL DEFAULT 'UNDISCLOSED'
    CHECK (gender IN ('FEMALE', 'MALE', 'TRANSGENDER', 'UNDISCLOSED'));
ALTER TABLE users ADD COLUMN avatar_kind VARCHAR(10) NOT NULL DEFAULT 'NONE'
    CHECK (avatar_kind IN ('NONE', 'PRESET', 'PHOTO'));
ALTER TABLE users ADD COLUMN avatar_preset VARCHAR(20) CHECK (avatar_preset IS NULL OR avatar_preset ~ '^a[0-9]{2}$');
ALTER TABLE users ADD COLUMN photo_version INT NOT NULL DEFAULT 0 CHECK (photo_version >= 0);
ALTER TABLE users ADD COLUMN adult_confirmed_at TIMESTAMPTZ;
ALTER TABLE users ADD COLUMN profile_completed_at TIMESTAMPTZ;

-- Accounts refused as under-18 are deleted at once. Their (pseudonymous) subject id is kept for one day only, so a
-- still-valid access token cannot silently recreate the record. No name, e-mail or birth date is kept.
CREATE TABLE refused_subjects (
    subject     UUID PRIMARY KEY,
    expires_at  TIMESTAMPTZ NOT NULL
);
