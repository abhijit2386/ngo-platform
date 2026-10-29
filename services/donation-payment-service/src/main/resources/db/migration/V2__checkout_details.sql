-- Donations session 1 (2026-10-29).
-- donor-service (NGO donor CRM) is not running yet: donor_id is filled later from the donation.paid event.
ALTER TABLE donations ALTER COLUMN donor_id DROP NOT NULL;
-- 80G (Form 10BE) needs the donor's PAN and address; display snapshots so history needs no cross-service calls.
ALTER TABLE donations
    ADD COLUMN donor_address  VARCHAR(300),
    ADD COLUMN ngo_name       VARCHAR(160),
    ADD COLUMN campaign_title VARCHAR(120);
ALTER TABLE donations ADD CONSTRAINT ck_80g_pan CHECK (NOT wants_80g OR donor_pan IS NOT NULL);
ALTER TABLE donations ADD CONSTRAINT ck_80g_address CHECK (NOT wants_80g OR channel <> 'ONLINE' OR donor_address IS NOT NULL);
