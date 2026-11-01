-- DEV/TEST ONLY: removes "E2E ..." documents, bills of E2E campaigns and meetings (document DB). Append-only / locked tables are opened
-- inside this transaction only. Storage usage is recomputed afterwards.
BEGIN;
ALTER TABLE document_activity DISABLE TRIGGER trg_document_activity_immutable;
ALTER TABLE meetings DISABLE TRIGGER trg_meetings_locked;
ALTER TABLE meeting_attendees DISABLE TRIGGER trg_attendees_locked;
ALTER TABLE meeting_resolutions DISABLE TRIGGER trg_resolutions_locked;
CREATE TEMP TABLE e2e_m ON COMMIT DROP AS SELECT id FROM meetings WHERE title LIKE 'E2E %';
CREATE TEMP TABLE e2e_d ON COMMIT DROP AS SELECT id FROM documents
  WHERE ((title LIKE 'E2E %' OR title LIKE 'Minutes - E2E %' OR link_id IN (SELECT id FROM e2e_m)) AND source IN ('UPLOAD','MEETING_MINUTES'))
     -- bills of E2E campaigns: the campaign cleanup deletes them directly, so no "removed" event reaches the vault
     OR (source = 'EXPENSE_BILL' AND link_label LIKE 'E2E %');
DELETE FROM document_activity WHERE document_id IN (SELECT id FROM e2e_d);
DELETE FROM documents WHERE id IN (SELECT id FROM e2e_d);
DELETE FROM meeting_actions WHERE meeting_id IN (SELECT id FROM e2e_m);
DELETE FROM meeting_resolutions WHERE meeting_id IN (SELECT id FROM e2e_m);
DELETE FROM meeting_attendees WHERE meeting_id IN (SELECT id FROM e2e_m);
DELETE FROM meetings WHERE id IN (SELECT id FROM e2e_m);
ALTER TABLE meeting_resolutions ENABLE TRIGGER trg_resolutions_locked;
ALTER TABLE meeting_attendees ENABLE TRIGGER trg_attendees_locked;
ALTER TABLE meetings ENABLE TRIGGER trg_meetings_locked;
ALTER TABLE document_activity ENABLE TRIGGER trg_document_activity_immutable;
UPDATE storage_usage u SET bytes = COALESCE(x.b, 0), files = COALESCE(x.n, 0), updated_at = now()
  FROM (SELECT s.ngo_id, sum(d.size_bytes) b, count(d.id) n FROM storage_usage s
        LEFT JOIN documents d ON d.ngo_id = s.ngo_id AND d.status <> 'PURGED' GROUP BY s.ngo_id) x
  WHERE x.ngo_id = u.ngo_id;
COMMIT;
