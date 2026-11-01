-- Meetings and minutes (owner request 2026-11-01: "how AGM and other MoM will be tracked").
-- SCHEDULED -> (record minutes) MINUTES_DRAFT -> (admin approves) MINUTES_APPROVED: locked, resolutions numbered
-- RES/<FY>/nnn, minutes PDF filed in the document vault. SCHEDULED -> CANCELLED with a reason.
CREATE TABLE meetings (
    id                  UUID PRIMARY KEY,
    ngo_id              UUID          NOT NULL,
    type                VARCHAR(12)   NOT NULL CHECK (type IN ('AGM','EGM','BOARD','COMMITTEE','STAFF','OTHER')),
    title               VARCHAR(200)  NOT NULL,
    starts_at           TIMESTAMPTZ   NOT NULL,
    mode                VARCHAR(10)   NOT NULL CHECK (mode IN ('IN_PERSON','ONLINE','HYBRID')),
    venue               VARCHAR(200),
    online_link         VARCHAR(300),
    agenda              VARCHAR(4000),
    -- TEAM: every member sees it; RESTRICTED: administrators and invited members only.
    visibility          VARCHAR(12)   NOT NULL CHECK (visibility IN ('TEAM','RESTRICTED')),
    financial_year      VARCHAR(7)    NOT NULL,
    status              VARCHAR(20)   NOT NULL CHECK (status IN ('SCHEDULED','MINUTES_DRAFT','MINUTES_APPROVED','CANCELLED')),
    quorum_required     INT           CHECK (quorum_required IS NULL OR quorum_required > 0),
    chairperson         VARCHAR(120),
    minutes             VARCHAR(20000),
    minutes_by          UUID,
    minutes_at          TIMESTAMPTZ,
    approved_by         UUID,
    approved_at         TIMESTAMPTZ,
    minutes_document_id UUID,
    cancel_reason       VARCHAR(500),
    created_by          UUID          NOT NULL,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version             BIGINT        NOT NULL DEFAULT 0
);
CREATE INDEX ix_meetings_ngo ON meetings (ngo_id, starts_at DESC);
CREATE INDEX ix_meetings_agm ON meetings (ngo_id, type, financial_year);

CREATE TABLE meeting_attendees (
    id          UUID PRIMARY KEY,
    meeting_id  UUID         NOT NULL REFERENCES meetings(id),
    user_id     UUID,                       -- team member; NULL for guests / members without an app account
    name        VARCHAR(120) NOT NULL,
    designation VARCHAR(80),
    present     BOOLEAN,                    -- NULL until attendance is recorded
    position    INT          NOT NULL DEFAULT 0
);
CREATE INDEX ix_attendees_meeting ON meeting_attendees (meeting_id, position);
CREATE INDEX ix_attendees_user ON meeting_attendees (user_id);

CREATE TABLE meeting_resolutions (
    id            UUID PRIMARY KEY,
    meeting_id    UUID          NOT NULL REFERENCES meetings(id),
    ngo_id        UUID          NOT NULL,
    number        VARCHAR(30),               -- RES/2026-27/001, given on approval
    text          VARCHAR(2000) NOT NULL,
    proposed_by   VARCHAR(120),
    seconded_by   VARCHAR(120),
    votes_for     INT CHECK (votes_for >= 0),
    votes_against INT CHECK (votes_against >= 0),
    abstained     INT CHECK (abstained >= 0),
    result        VARCHAR(10)   NOT NULL CHECK (result IN ('PASSED','REJECTED','DEFERRED')),
    position      INT           NOT NULL DEFAULT 0
);
CREATE INDEX ix_resolutions_meeting ON meeting_resolutions (meeting_id, position);
CREATE UNIQUE INDEX uq_resolution_number ON meeting_resolutions (ngo_id, number) WHERE number IS NOT NULL;

CREATE TABLE resolution_counters (
    ngo_id  UUID       NOT NULL,
    fy      VARCHAR(7) NOT NULL,
    last_no INT        NOT NULL DEFAULT 0,
    PRIMARY KEY (ngo_id, fy)
);

CREATE TABLE meeting_actions (
    id            UUID PRIMARY KEY,
    meeting_id    UUID         NOT NULL REFERENCES meetings(id),
    ngo_id        UUID         NOT NULL,
    text          VARCHAR(500) NOT NULL,
    owner_user_id UUID,
    owner_name    VARCHAR(120),
    due_date      DATE,
    status        VARCHAR(6)   NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','DONE')),
    done_by       UUID,
    done_at       TIMESTAMPTZ,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_actions_owner ON meeting_actions (ngo_id, owner_user_id, status);
CREATE INDEX ix_actions_meeting ON meeting_actions (meeting_id);

-- Approved minutes are a legal record: the meeting row, attendance and resolutions cannot change afterwards
-- (action items keep their own status so follow-up can still be ticked off).
CREATE FUNCTION meeting_minutes_locked() RETURNS trigger AS $$
DECLARE st VARCHAR(20);
BEGIN
    IF TG_TABLE_NAME = 'meetings' THEN
        IF OLD.status = 'MINUTES_APPROVED' THEN
            RAISE EXCEPTION 'approved minutes are locked';
        END IF;
        RETURN NEW;
    END IF;
    SELECT status INTO st FROM meetings WHERE id = COALESCE(NEW.meeting_id, OLD.meeting_id);
    IF st = 'MINUTES_APPROVED' THEN
        RAISE EXCEPTION 'approved minutes are locked';
    END IF;
    RETURN COALESCE(NEW, OLD);
END $$ LANGUAGE plpgsql;
CREATE TRIGGER trg_meetings_locked BEFORE UPDATE OR DELETE ON meetings
    FOR EACH ROW EXECUTE FUNCTION meeting_minutes_locked();
CREATE TRIGGER trg_attendees_locked BEFORE INSERT OR UPDATE OR DELETE ON meeting_attendees
    FOR EACH ROW EXECUTE FUNCTION meeting_minutes_locked();
CREATE TRIGGER trg_resolutions_locked BEFORE INSERT OR UPDATE OR DELETE ON meeting_resolutions
    FOR EACH ROW EXECUTE FUNCTION meeting_minutes_locked();
