-- App renamed from "Seva Setu" to "Yogdaan" (owner decision 2026-11-02, domain yogdaan.co.in).
-- Earlier template migrations stay untouched (Flyway checksums); the stored texts are renamed here. Templates an
-- admin edited are renamed too, since only the app name changes.
UPDATE notification_templates
SET subject    = replace(replace(subject, 'Seva Setu', 'Yogdaan'), 'सेवा सेतु', 'योगदान'),
    body       = replace(replace(body, 'Seva Setu', 'Yogdaan'), 'सेवा सेतु', 'योगदान'),
    updated_at = now(),
    version    = version + 1
WHERE subject LIKE '%Seva Setu%' OR body LIKE '%Seva Setu%' OR subject LIKE '%सेवा सेतु%' OR body LIKE '%सेवा सेतु%';
