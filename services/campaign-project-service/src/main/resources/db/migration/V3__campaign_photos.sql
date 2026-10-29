-- Up to 5 photos per campaign (owner request 2026-10-29: "4 to 5 photos to build sympathy").
-- position 0 is the cover; campaigns.image_key keeps the cover's key for list views and older clients.
CREATE TABLE campaign_photos (
    id          UUID PRIMARY KEY,
    campaign_id UUID         NOT NULL REFERENCES campaigns(id) ON DELETE CASCADE,
    position    INT          NOT NULL CHECK (position BETWEEN 0 AND 9),
    image_key   VARCHAR(300) NOT NULL,
    created_by  UUID,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_campaign_photos ON campaign_photos (campaign_id, position);

-- Existing covers become photo #1.
INSERT INTO campaign_photos (id, campaign_id, position, image_key, created_by)
SELECT gen_random_uuid(), id, 0, image_key, created_by FROM campaigns WHERE image_key IS NOT NULL;
