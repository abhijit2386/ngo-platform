-- Design names only need to be unique among designs that are not archived (an archived "Our receipt" must not block a
-- new "Our receipt").
ALTER TABLE receipt_templates DROP CONSTRAINT IF EXISTS uq_template_name;
CREATE UNIQUE INDEX uq_template_name_live ON receipt_templates (ngo_id, lower(name)) WHERE status <> 'ARCHIVED';
