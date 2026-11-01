package org.ngoplatform.document.app;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.ngoplatform.commons.audit.AuditLog;
import org.ngoplatform.commons.error.ApiException;
import org.ngoplatform.commons.storage.ObjectStorage;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CSR compliance pack (owner decision 2026-11-02, Enterprise): vault documents shared with a company through one
 * expiring link. A pack only points at vault documents (nothing is copied); sharing freezes the exact stored file of
 * each document (storage key + SHA-256), and {@link DocumentService#purge} keeps files a live link still uses.
 */
@Service
public class CsrService {
    public record Pack(UUID id, UUID ngoId, String title, String recipientName, String recipientEmail, String message,
                       int validDays, String status, String code, Instant sharedAt, UUID sharedBy, Instant expiresAt,
                       Instant revokedAt, UUID revokedBy, UUID createdBy, Instant createdAt, Instant updatedAt, long version) {
        /** SHARED packs whose link time is over are shown as EXPIRED. */
        public String effectiveStatus(Instant now) {
            return "SHARED".equals(status) && expiresAt != null && now.isAfter(expiresAt) ? "EXPIRED" : status;
        }
        public boolean live(Instant now) { return "SHARED".equals(status) && expiresAt != null && now.isBefore(expiresAt); }
    }

    public record Item(UUID id, UUID packId, UUID documentId, String kind, int position, String title, String fileType,
                       String contentType, Long sizeBytes, String sha256, String storageKey, LocalDate expiresOn, Instant frozenAt) {}

    public record Input(String title, String recipientName, String recipientEmail, String message, Integer validDays) {}

    private static final String PACK_COLS = "id, ngo_id, title, recipient_name, recipient_email, message, valid_days, status, code, "
            + "shared_at, shared_by, expires_at, revoked_at, revoked_by, created_by, created_at, updated_at, version";
    private static final RowMapper<Pack> PACK = (rs, i) -> new Pack(rs.getObject("id", UUID.class), rs.getObject("ngo_id", UUID.class),
            rs.getString("title"), rs.getString("recipient_name"), rs.getString("recipient_email"), rs.getString("message"),
            rs.getInt("valid_days"), rs.getString("status"), rs.getString("code"), ts(rs.getTimestamp("shared_at")),
            rs.getObject("shared_by", UUID.class), ts(rs.getTimestamp("expires_at")), ts(rs.getTimestamp("revoked_at")),
            rs.getObject("revoked_by", UUID.class), rs.getObject("created_by", UUID.class), ts(rs.getTimestamp("created_at")),
            ts(rs.getTimestamp("updated_at")), rs.getLong("version"));
    private static final String ITEM_COLS = "id, pack_id, document_id, kind, position, title, file_type, content_type, size_bytes, "
            + "sha256, storage_key, expires_on, frozen_at";
    private static final RowMapper<Item> ITEM = (rs, i) -> new Item(rs.getObject("id", UUID.class), rs.getObject("pack_id", UUID.class),
            rs.getObject("document_id", UUID.class), rs.getString("kind"), rs.getInt("position"), rs.getString("title"),
            rs.getString("file_type"), rs.getString("content_type"), (Long) rs.getObject("size_bytes"), rs.getString("sha256"),
            rs.getString("storage_key"), rs.getObject("expires_on", LocalDate.class), ts(rs.getTimestamp("frozen_at")));

    private static Instant ts(Timestamp t) { return t == null ? null : t.toInstant(); }

    private final JdbcTemplate jdbc;
    private final DocumentService docs;
    private final ObjectStorage storage;
    private final AuditLog audit;
    private final Clock clock;

    public CsrService(JdbcTemplate jdbc, DocumentService docs, ObjectStorage storage, AuditLog audit, Clock clock) {
        this.jdbc = jdbc; this.docs = docs; this.storage = storage; this.audit = audit; this.clock = clock;
    }

    // ------------------------------------------------------------------ reading

    public List<Pack> list(UUID ngoId) {
        return jdbc.query("select " + PACK_COLS + " from csr_packs where ngo_id = ? order by created_at desc limit 200", PACK, ngoId);
    }

    public Pack pack(UUID ngoId, UUID id) {
        List<Pack> l = jdbc.query("select " + PACK_COLS + " from csr_packs where id = ? and ngo_id = ?", PACK, id, ngoId);
        if (l.isEmpty()) throw ApiException.notFound("CSR pack");
        return l.getFirst();
    }

    public List<Item> items(UUID packId) {
        return jdbc.query("select " + ITEM_COLS + " from csr_pack_items where pack_id = ? order by position, id", ITEM, packId);
    }

    /** Documents of a pack as they are now in the vault (title, status and expiry may have changed since adding). */
    public List<Map<String, Object>> itemViews(Pack p) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Item i : items(p.id())) {
            Map<String, Object> m = new LinkedHashMap<>();
            DocumentService.Doc d = docOrNull(i.documentId());
            m.put("id", i.id());
            m.put("documentId", i.documentId());
            m.put("kind", i.kind());
            m.put("kindLabel", CsrRules.kind(i.kind()).label());
            m.put("title", i.frozenAt() != null ? i.title() : d == null ? "" : d.title());
            m.put("fileType", i.frozenAt() != null ? i.fileType() : d == null ? null : d.fileType());
            m.put("sizeBytes", i.frozenAt() != null ? i.sizeBytes() : d == null ? null : d.sizeBytes());
            m.put("expiresOn", i.frozenAt() != null ? i.expiresOn() : d == null ? null : d.expiresOn());
            m.put("documentStatus", d == null ? "PURGED" : d.status());
            if (i.frozenAt() != null) { m.put("sha256", i.sha256()); m.put("frozenAt", i.frozenAt()); }
            out.add(m);
        }
        return out;
    }

    public CsrRules.Readiness readiness(Pack p) {
        List<CsrRules.ItemState> states = new ArrayList<>();
        for (Item i : items(p.id())) {
            DocumentService.Doc d = docOrNull(i.documentId());
            states.add(new CsrRules.ItemState(i.kind(), d == null ? i.title() : d.title(), d == null ? "PURGED" : d.status(),
                    d == null ? i.expiresOn() : d.expiresOn()));
        }
        return CsrRules.readiness(states, docs.today());
    }

    /** Per kind, the vault documents that most likely prove it (newest first, at most 3). */
    public Map<String, List<Map<String, Object>>> suggestions(UUID ngoId) {
        Map<String, List<Map<String, Object>>> out = new LinkedHashMap<>();
        for (CsrRules.Kind k : CsrRules.KINDS) out.put(k.code(), new ArrayList<>());
        List<DocumentService.Doc> all = jdbc.query("select " + DocumentService.COLS + " from documents where ngo_id = ? and status = 'ACTIVE' "
                + "and category in ('LEGAL_REGISTRATION','TAX_COMPLIANCE','FINANCE','CSR','PHOTOS_REPORTS','AGREEMENT','OTHER') "
                + "order by coalesce(document_date, created_at::date) desc, created_at desc limit 500", DocumentService.ROW, ngoId);
        for (DocumentService.Doc d : all) {
            String k = CsrRules.guessKind(d.title(), d.tags());
            if (k == null || out.get(k).size() >= 3) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("documentId", d.id());
            m.put("title", d.title());
            m.put("fileType", d.fileType());
            m.put("documentDate", d.documentDate());
            m.put("expiresOn", d.expiresOn());
            m.put("expired", d.expiresOn() != null && d.expiresOn().isBefore(docs.today()));
            out.get(k).add(m);
        }
        return out;
    }

    public List<Map<String, Object>> views(UUID packId) {
        return jdbc.queryForList("select v.action, v.at, v.client, i.title as \"itemTitle\" from csr_pack_views v "
                + "left join csr_pack_items i on i.id = v.item_id where v.pack_id = ? order by v.at desc limit 200", packId);
    }

    public Map<String, Object> viewSummary(UUID packId) {
        return jdbc.queryForMap("select count(*) filter (where action = 'OPENED') as opened, "
                + "count(*) filter (where action <> 'OPENED') as downloads, max(at) as \"lastAt\" from csr_pack_views where pack_id = ?", packId);
    }

    // ------------------------------------------------------------------ writing (NGO administrators)

    /** New draft; with autoFill the best vault document of each kind is added straight away. */
    @Transactional
    public Pack create(UUID ngoId, UUID actor, Input in, boolean autoFill) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into csr_packs(id, ngo_id, title, recipient_name, recipient_email, message, valid_days, created_by) "
                        + "values (?,?,?,?,?,?,?,?)", id, ngoId, CsrRules.title(in.title()), CsrRules.recipient(in.recipientName()),
                CsrRules.email(in.recipientEmail()), CsrRules.message(in.message()), CsrRules.validDays(in.validDays()), actor);
        if (autoFill) {
            int pos = 0;
            for (Map.Entry<String, List<Map<String, Object>>> e : suggestions(ngoId).entrySet()) {
                Map<String, Object> best = e.getValue().stream().filter(m -> !Boolean.TRUE.equals(m.get("expired"))).findFirst().orElse(null);
                if (best == null) continue;
                jdbc.update("insert into csr_pack_items(id, pack_id, document_id, kind, position) values (?,?,?,?,?) on conflict do nothing",
                        UUID.randomUUID(), id, best.get("documentId"), e.getKey(), pos++);
            }
        }
        audit.record(ngoId, actor, "CSR_PACK_CREATED", "CSR_PACK", id, Map.of());
        return pack(ngoId, id);
    }

    @Transactional
    public Pack update(UUID ngoId, UUID actor, UUID id, Input in, long expectedVersion) {
        Pack p = draft(ngoId, id);
        int n = jdbc.update("update csr_packs set title = ?, recipient_name = ?, recipient_email = ?, message = ?, valid_days = ?, "
                        + "updated_at = now(), version = version + 1 where id = ? and version = ?", CsrRules.title(in.title()),
                CsrRules.recipient(in.recipientName()), CsrRules.email(in.recipientEmail()), CsrRules.message(in.message()),
                CsrRules.validDays(in.validDays()), p.id(), expectedVersion);
        if (n == 0) throw ApiException.conflict("STALE", "Someone else changed this pack. Reload it and try again.");
        return pack(ngoId, id);
    }

    @Transactional
    public Pack addItem(UUID ngoId, UUID actor, UUID id, UUID documentId, String kind) {
        Pack p = draft(ngoId, id);
        CsrRules.kind(kind);
        DocumentService.Doc d = docs.find(ngoId, documentId);
        if (!"ACTIVE".equals(d.status())) throw ApiException.rule("DOCUMENT_DELETED", "Restore the document first");
        List<Item> current = items(p.id());
        if (current.stream().anyMatch(i -> i.documentId().equals(documentId))) {
            throw ApiException.conflict("ALREADY_IN_PACK", "This document is already in the pack");
        }
        if (current.size() >= CsrRules.MAX_ITEMS) throw ApiException.rule("TOO_MANY_DOCUMENTS", "A pack can hold up to 30 documents");
        int pos = current.stream().mapToInt(Item::position).max().orElse(-1) + 1;
        jdbc.update("insert into csr_pack_items(id, pack_id, document_id, kind, position) values (?,?,?,?,?)",
                UUID.randomUUID(), p.id(), documentId, kind, pos);
        touch(p.id());
        return pack(ngoId, id);
    }

    @Transactional
    public Pack removeItem(UUID ngoId, UUID actor, UUID id, UUID itemId) {
        Pack p = draft(ngoId, id);
        if (jdbc.update("delete from csr_pack_items where id = ? and pack_id = ?", itemId, p.id()) == 0) throw ApiException.notFound("Document in pack");
        touch(p.id());
        return pack(ngoId, id);
    }

    /**
     * Freezes the stored file of every document and opens the company's link. Errors (deleted / expired document,
     * empty pack) block; warnings (missing papers, expiring soon) must be accepted by the caller.
     */
    @Transactional
    public Pack share(UUID ngoId, UUID actor, UUID id, long expectedVersion, boolean acceptWarnings) {
        Pack p = draft(ngoId, id);
        if (p.version() != expectedVersion) throw ApiException.conflict("STALE", "Someone else changed this pack. Reload it and try again.");
        CsrRules.Readiness r = readiness(p);
        if (!r.errors().isEmpty()) {
            throw ApiException.rule("PACK_NOT_READY", "Fix these first: " + String.join("; ", r.errors()));
        }
        if (!r.warnings().isEmpty() && !acceptWarnings) {
            throw ApiException.rule("PACK_HAS_WARNINGS", "Please confirm: " + String.join("; ", r.warnings()));
        }
        Instant now = clock.instant();
        for (Item i : items(p.id())) {
            DocumentService.Doc d = docs.find(ngoId, i.documentId());
            String sha = jdbc.queryForObject("select sha256 from documents where id = ?", String.class, d.id());
            if (sha == null) sha = DocFiles.sha256(docs.read(d)); // files registered by other modules have no hash yet
            jdbc.update("update csr_pack_items set title = ?, file_type = ?, content_type = ?, size_bytes = ?, sha256 = ?, storage_key = ?, "
                            + "expires_on = ?, frozen_at = ? where id = ?", d.title(), d.fileType(), d.contentType(), d.sizeBytes(), sha,
                    d.storageKey(), d.expiresOn(), Timestamp.from(now), i.id());
            docs.record(ngoId, d.id(), actor, "LINK_CREATED");
        }
        jdbc.update("update csr_packs set status = 'SHARED', code = ?, shared_at = ?, shared_by = ?, expires_at = ?, updated_at = now(), "
                        + "version = version + 1 where id = ?", CsrRules.newCode(), Timestamp.from(now), actor,
                Timestamp.from(now.plus(Duration.ofDays(p.validDays()))), p.id());
        audit.record(ngoId, actor, "CSR_PACK_SHARED", "CSR_PACK", id, Map.of("recipient", p.recipientName(), "documents", items(p.id()).size()));
        return pack(ngoId, id);
    }

    @Transactional
    public Pack revoke(UUID ngoId, UUID actor, UUID id) {
        Pack p = pack(ngoId, id);
        if (!"SHARED".equals(p.status())) throw ApiException.rule("NOT_SHARED", "Only a shared pack can be stopped");
        jdbc.update("update csr_packs set status = 'REVOKED', revoked_at = now(), revoked_by = ?, updated_at = now(), version = version + 1 "
                + "where id = ?", actor, id);
        audit.record(ngoId, actor, "CSR_PACK_REVOKED", "CSR_PACK", id, Map.of());
        return pack(ngoId, id);
    }

    /** A new draft with the same company and documents (to share again after a link expired or was stopped). */
    @Transactional
    public Pack copy(UUID ngoId, UUID actor, UUID id) {
        Pack p = pack(ngoId, id);
        Pack n = create(ngoId, actor, new Input(p.title(), p.recipientName(), p.recipientEmail(), p.message(), p.validDays()), false);
        for (Item i : items(p.id())) {
            DocumentService.Doc d = docOrNull(i.documentId());
            if (d == null || !"ACTIVE".equals(d.status())) continue;
            jdbc.update("insert into csr_pack_items(id, pack_id, document_id, kind, position) values (?,?,?,?,?)",
                    UUID.randomUUID(), n.id(), i.documentId(), i.kind(), i.position());
        }
        return pack(ngoId, n.id());
    }

    private Pack draft(UUID ngoId, UUID id) {
        Pack p = pack(ngoId, id);
        if (!"DRAFT".equals(p.status())) throw ApiException.rule("PACK_ALREADY_SHARED",
                "This pack has been shared and cannot be changed. Make a copy to send an updated pack.");
        return p;
    }

    private void touch(UUID packId) { jdbc.update("update csr_packs set updated_at = now(), version = version + 1 where id = ?", packId); }

    private DocumentService.Doc docOrNull(UUID documentId) {
        try { return docs.byId(documentId); } catch (ApiException e) { return null; }
    }

    // ------------------------------------------------------------------ the company's side (public link)

    /** The live pack behind a link; revoked or expired links answer 410 Gone with a clear message. */
    public Pack byCode(String code) {
        if (!CsrRules.validCode(code)) throw ApiException.notFound("Link");
        List<Pack> l = jdbc.query("select " + PACK_COLS + " from csr_packs where code = ?", PACK, code);
        if (l.isEmpty()) throw ApiException.notFound("Link");
        Pack p = l.getFirst();
        if ("REVOKED".equals(p.status())) throw new ApiException(HttpStatus.GONE, "LINK_REVOKED", "The organisation has withdrawn this link.");
        if (!p.live(clock.instant())) throw new ApiException(HttpStatus.GONE, "LINK_EXPIRED", "This link has expired. Please ask the organisation for a new one.");
        return p;
    }

    public void logView(UUID packId, UUID itemId, String action, String client) {
        String c = client == null ? null : client.length() > 120 ? client.substring(0, 120) : client;
        jdbc.update("insert into csr_pack_views(pack_id, item_id, action, client) values (?,?,?,?)", packId, itemId, action, c);
    }

    public Item frozenItem(Pack p, UUID itemId) {
        return items(p.id()).stream().filter(i -> i.id().equals(itemId) && i.frozenAt() != null).findFirst()
                .orElseThrow(() -> ApiException.notFound("Document"));
    }

    /** Whether the stored file is still there (another module may have replaced a registration document). */
    public boolean available(Item i) {
        try (InputStream in = storage.get(i.storageKey())) { return in != null; } catch (IOException | RuntimeException e) { return false; }
    }

    public InputStream open(Item i) {
        try { return storage.get(i.storageKey()); }
        catch (RuntimeException e) { throw new ApiException(HttpStatus.GONE, "FILE_WITHDRAWN", "The organisation has withdrawn this document."); }
    }

    /** All available documents of the pack as one ZIP, written straight to the response (nothing is stored). */
    public void writeZip(Pack p, OutputStream out) throws IOException {
        Set<String> names = new HashSet<>();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            int n = 0;
            for (Item i : items(p.id()).stream().filter(x -> x.frozenAt() != null).sorted(Comparator.comparingInt(Item::position)).toList()) {
                byte[] bytes;
                try (InputStream in = storage.get(i.storageKey())) { bytes = in.readAllBytes(); }
                catch (IOException | RuntimeException e) { continue; }
                String base = String.format("%02d %s", ++n, DocFiles.downloadName(i.title(), i.storageKey())).replaceAll("[\\\\/:*?\"<>|]", "_");
                String name = base;
                for (int k = 2; !names.add(name); k++) name = k + " " + base;
                zip.putNextEntry(new ZipEntry(name));
                zip.write(bytes);
                zip.closeEntry();
            }
        }
    }
}
