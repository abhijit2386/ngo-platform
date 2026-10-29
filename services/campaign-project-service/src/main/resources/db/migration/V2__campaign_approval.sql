-- Campaign approval workflow (owner decision 2026-10-29): any team member drafts and submits,
-- an NGO administrator approves (publishes) or sends it back with a reason.
ALTER TABLE campaigns
    ADD COLUMN submitted_by     UUID,
    ADD COLUMN submitted_at     TIMESTAMPTZ,
    ADD COLUMN rejection_reason VARCHAR(500),
    ADD COLUMN rejected_by      UUID,
    ADD COLUMN rejected_at      TIMESTAMPTZ,
    ADD COLUMN updated_by       UUID,
    -- Public display snapshot taken at approval (donor app lists campaigns without calling the registry).
    ADD COLUMN ngo_name         VARCHAR(160),
    ADD COLUMN ngo_city         VARCHAR(80),
    ADD COLUMN image_version    INT NOT NULL DEFAULT 0;

ALTER TABLE campaigns ADD CONSTRAINT ck_campaign_cause CHECK (cause IS NULL OR cause IN (
    'EDUCATION', 'HEALTH', 'WOMEN_EMPOWERMENT', 'CHILD_WELFARE', 'ELDERLY_CARE', 'DISABILITY', 'ENVIRONMENT',
    'ANIMAL_WELFARE', 'RURAL_DEVELOPMENT', 'DISASTER_RELIEF', 'LIVELIHOOD', 'HUNGER_NUTRITION', 'WATER_SANITATION',
    'ARTS_CULTURE', 'SPORTS', 'OTHER'));

-- Donations credited to a campaign. Real ones arrive from donation-payment-service (donation.donation.paid);
-- is_test rows are recorded by staff in development until online payments exist.
ALTER TABLE campaign_donations
    ADD COLUMN is_test     BOOLEAN     NOT NULL DEFAULT false,
    ADD COLUMN donor_label VARCHAR(80),
    ADD COLUMN recorded_by UUID,
    ADD COLUMN created_at  TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE campaign_donations ADD CONSTRAINT ck_cd_amount CHECK (amount > 0);
CREATE INDEX ix_cd_campaign ON campaign_donations (campaign_id, created_at DESC);
