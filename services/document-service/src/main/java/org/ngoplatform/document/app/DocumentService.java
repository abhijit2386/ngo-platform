package org.ngoplatform.document.app;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.ngoplatform.commons.audit.AuditLog;
import org.ngoplatform.commons.error.ApiException;
import org.ngoplatform.commons.security.EntitlementResolver;
import org.ngoplatform.commons.security.Entitlements;
import org.ngoplatform.commons.security.Membership;
import org.ngoplatform.commons.storage.ObjectStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The NGO's document vault. Every file of the NGO is listed here (uploads, and files kept by other modules such as
 * registration documents and expense bills); the bytes stay in the shared private object store under ngos/&lt;id&gt;/.
 * Access: see {@link DocumentRules#canSee}. Every open, download and change is written to document_activity.
 */
@Service
public class DocumentService {
    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);
    static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    private static final char SEP = (char) 31;

    public record Doc(UUID id, UUID ngoId, String title, String description, String category, List<String> tags,
                      String fileType, String contentType, long sizeBytes, String originalName, String storageKey,
                      String source, String visibility, String requiredPermission, String linkType, UUID linkId,
                      UUID linkParentId, String linkLabel, LocalDate documentDate, LocalDate expiresOn, UUID uploadedBy,
                      UUID updatedBy, String status, UUID deletedBy, Instant deletedAt, LocalDate purgeAfter,
                      Instant createdAt, Instant updatedAt, long version) {
        DocumentRules.Doc rules() { return new DocumentRules.Doc(uploadedBy, visibility, requiredPermission, source); }
    }

    public record UploadInput(String title, String description, String category, List<String> tags, String visibility,
                              LocalDate documentDate, LocalDate expiresOn, String linkType, UUID linkId, UUID linkParentId,
                              String linkLabel) {}

    public record Filter(String q, String category, String fileType, LocalDate from, LocalDate to, UUID uploadedBy,
                         String tag, String linkType, UUID linkId, boolean mine, String sort) {}

    public record Page(List<Doc> items, long total, int page, int size) {}

    public record Usage(long usedBytes, int files, long limitBytes) {}

    /** A file kept by another service, announced by event (document.file.registered) or listed at backfill. */
    public record External(UUID ngoId, String source, String sourceRef, String storageKey, String title, String category,
                           String contentType, long sizeBytes, String originalName, UUID uploadedBy, String visibility,
                           String requiredPermission, String linkType, UUID linkId, UUID linkParentId, String linkLabel,
                           LocalDate documentDate, LocalDate expiresOn, Instant createdAt) {}

    private final JdbcTemplate jdbc;
    private final ObjectStorage storage;
    private final EntitlementResolver entitlements;
    private final AuditLog audit;
    private final Clock clock;
    private final long maxBytes;

    public DocumentService(JdbcTemplate jdbc, ObjectStorage storage, EntitlementResolver entitlements, AuditLog audit, Clock clock,
                           @Value("${ngo.documents.max-upload-mb:25}") int maxUploadMb) {
        this.jdbc = jdbc; this.storage = storage; this.entitlements = entitlements; this.audit = audit; this.clock = clock;
        this.maxBytes = maxUploadMb * 1024L * 1024L;
    }

    public LocalDate today() { return LocalDate.now(clock.withZone(INDIA)); }

    // ---------------- reading

    static final String COLS = "id, ngo_id, title, description, category, tags, file_type, content_type, size_bytes, original_name, "
            + "storage_key, source, visibility, required_permission, link_type, link_id, link_parent_id, link_label, document_date, "
            + "expires_on, uploaded_by, updated_by, status, deleted_by, deleted_at, purge_after, created_at, updated_at, version";

    static final RowMapper<Doc> ROW = (rs, i) -> new Doc(rs.getObject("id", UUID.class), rs.getObject("ngo_id", UUID.class),
            rs.getString("title"), rs.getString("description"), rs.getString("category"), tags(rs), rs.getString("file_type"),
            rs.getString("content_type"), rs.getLong("size_bytes"), rs.getString("original_name"), rs.getString("storage_key"),
            rs.getString("source"), rs.getString("visibility"), rs.getString("required_permission"), rs.getString("link_type"),
            rs.getObject("link_id", UUID.class), rs.getObject("link_parent_id", UUID.class), rs.getString("link_label"),
            rs.getObject("document_date", LocalDate.class), rs.getObject("expires_on", LocalDate.class),
            rs.getObject("uploaded_by", UUID.class), rs.getObject("updated_by", UUID.class), rs.getString("status"),
            rs.getObject("deleted_by", UUID.class), ts(rs, "deleted_at"), rs.getObject("purge_after", LocalDate.class),
            ts(rs, "created_at"), ts(rs, "updated_at"), rs.getLong("version"));

    private static List<String> tags(ResultSet rs) throws SQLException {
        Array a = rs.getArray("tags");
        return a == null ? List.of() : Arrays.asList((String[]) a.getArray());
    }

    private static Instant ts(ResultSet rs, String col) throws SQLException {
        java.sql.Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }

    Doc find(UUID ngoId, UUID id) {
        List<Doc> l = jdbc.query("select " + COLS + " from documents where id = ? and ngo_id = ? and status <> 'PURGED'", ROW, id, ngoId);
        if (l.isEmpty()) throw ApiException.notFound("Document");
        return l.getFirst();
    }

    /** Any non-purged document by id (signed download links re-check access themselves). */
    public Doc byId(UUID id) {
        List<Doc> l = jdbc.query("select " + COLS + " from documents where id = ? and status <> 'PURGED'", ROW, id);
        if (l.isEmpty()) throw ApiException.notFound("Document");
        return l.getFirst();
    }

    /** A document the member may see (deleted ones only for those who may restore them). */
    public Doc get(UUID ngoId, Membership m, UUID id) {
        Doc d = find(ngoId, id);
        if (!DocumentRules.canSee(m, d.rules())) throw ApiException.notFound("Document");
        return d;
    }

    public Page search(UUID ngoId, Membership m, Filter f, int page, int size) {
        StringBuilder where = new StringBuilder(" from documents where ngo_id = ? and status = 'ACTIVE'");
        List<Object> args = new ArrayList<>(List.of(ngoId));
        visibilityClause(m, where, args);
        if (f.q() != null && !f.q().isBlank()) {
            String q = f.q().strip().toLowerCase(Locale.ROOT);
            if (q.length() > 80) throw ApiException.badRequest("QUERY_TOO_LONG", "Search text is too long");
            String like = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            where.append(" and (lower(title) like ? or lower(coalesce(original_name, '')) like ? or lower(coalesce(link_label, '')) like ?"
                    + " or lower(coalesce(description, '')) like ? or ? = any(tags))");
            args.addAll(List.of(like, like, like, like, q.startsWith("#") ? q.substring(1) : q));
        }
        if (f.category() != null) { where.append(" and category = ?"); args.add(DocumentRules.category(f.category())); }
        if (f.fileType() != null) {
            if (!DocumentRules.FILE_TYPES.contains(f.fileType())) throw ApiException.badRequest("INVALID_TYPE", "Unknown file type");
            where.append(" and file_type = ?"); args.add(f.fileType());
        }
        if (f.from() != null) { where.append(" and coalesce(document_date, (created_at at time zone 'Asia/Kolkata')::date) >= ?"); args.add(f.from()); }
        if (f.to() != null) { where.append(" and coalesce(document_date, (created_at at time zone 'Asia/Kolkata')::date) <= ?"); args.add(f.to()); }
        if (f.uploadedBy() != null) { where.append(" and uploaded_by = ?"); args.add(f.uploadedBy()); }
        if (f.mine()) { where.append(" and uploaded_by = ?"); args.add(m.userId()); }
        if (f.tag() != null && !f.tag().isBlank()) { where.append(" and ? = any(tags)"); args.add(f.tag().strip().toLowerCase(Locale.ROOT)); }
        if (f.linkType() != null) { where.append(" and link_type = ?"); args.add(f.linkType()); }
        if (f.linkId() != null) { where.append(" and (link_id = ? or link_parent_id = ?)"); args.add(f.linkId()); args.add(f.linkId()); }
        String order = switch (f.sort() == null ? "" : f.sort()) {
            case "name" -> " order by lower(title), created_at desc";
            case "date" -> " order by coalesce(document_date, (created_at at time zone 'Asia/Kolkata')::date) desc, created_at desc";
            case "size" -> " order by size_bytes desc";
            default -> " order by created_at desc";
        };
        Long total = jdbc.queryForObject("select count(*)" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((long) page * size);
        List<Doc> items = jdbc.query("select " + COLS + where + order + " limit ? offset ?", ROW, pageArgs.toArray());
        return new Page(items, total == null ? 0 : total, page, size);
    }

    /** Number of visible documents per category (filter chips). */
    public Map<String, Long> categoryCounts(UUID ngoId, Membership m) {
        StringBuilder where = new StringBuilder(" from documents where ngo_id = ? and status = 'ACTIVE'");
        List<Object> args = new ArrayList<>(List.of(ngoId));
        visibilityClause(m, where, args);
        Map<String, Long> out = new LinkedHashMap<>();
        DocumentRules.CATEGORIES.forEach(c -> out.put(c, 0L));
        jdbc.query("select category, count(*)" + where + " group by category", rs -> { out.put(rs.getString(1), rs.getLong(2)); }, args.toArray());
        return out;
    }

    /** Tags in use (for suggestions), most used first. */
    public List<String> tags(UUID ngoId, Membership m) {
        StringBuilder where = new StringBuilder(" from documents, unnest(tags) t where ngo_id = ? and status = 'ACTIVE'");
        List<Object> args = new ArrayList<>(List.of(ngoId));
        visibilityClause(m, where, args);
        return jdbc.queryForList("select t" + where + " group by t order by count(*) desc, t limit 50", String.class, args.toArray());
    }

    private static void visibilityClause(Membership m, StringBuilder where, List<Object> args) {
        if (m.isAdmin()) return;
        List<String> perms = new ArrayList<>(m.permissions());
        where.append(" and (uploaded_by = ? or (visibility = 'TEAM' and (required_permission is null");
        args.add(m.userId());
        if (!perms.isEmpty()) {
            where.append(" or required_permission in (").append(String.join(",", java.util.Collections.nCopies(perms.size(), "?"))).append(")");
            args.addAll(perms);
        }
        where.append(")))");
    }

    public Usage usage(UUID ngoId) {
        List<Usage> u = jdbc.query("select bytes, files from storage_usage where ngo_id = ?",
                (rs, i) -> new Usage(rs.getLong(1), rs.getInt(2), 0), ngoId);
        long used = u.isEmpty() ? 0 : u.getFirst().usedBytes();
        int files = u.isEmpty() ? 0 : u.getFirst().files();
        int mb = limitMb(ngoId);
        return new Usage(used, files, mb < 0 ? -1 : mb * 1024L * 1024L);
    }

    private int limitMb(UUID ngoId) {
        try {
            return entitlements.get(ngoId).limitOr(Entitlements.STORAGE_MB_MAX, DocumentRules.DEFAULT_STORAGE_MB);
        } catch (RuntimeException e) {
            return DocumentRules.DEFAULT_STORAGE_MB; // subscription service unreachable: use the smallest plan's limit
        }
    }

    public InputStream open(Doc d) { return storage.get(d.storageKey()); }

    public List<Map<String, Object>> activity(UUID ngoId, UUID id) {
        return jdbc.queryForList("select user_id as \"userId\", action, at from document_activity where ngo_id = ? and document_id = ? "
                + "order by at desc limit 100", ngoId, id);
    }

    public void record(UUID ngoId, UUID docId, UUID userId, String action) {
        jdbc.update("insert into document_activity(ngo_id, document_id, user_id, action) values (?,?,?,?)", ngoId, docId, userId, action);
    }

    // ---------------- writing

    @Transactional
    public Doc upload(UUID ngoId, Membership m, UploadInput in, byte[] bytes, String originalName) {
        String category = DocumentRules.category(in.category());
        String title = DocumentRules.title(in.title(), stripExtension(originalName));
        List<String> tags = DocumentRules.tags(in.tags());
        String visibility = DocumentRules.visibility(in.visibility(), category);
        DocumentRules.checkDates(in.documentDate(), in.expiresOn(), today());
        String description = limit(in.description(), 1000);
        if (in.linkType() != null && !DocumentRules.LINK_TYPES.contains(in.linkType())) throw ApiException.badRequest("INVALID_LINK", "Unknown link");
        DocFiles.Checked f = DocFiles.check(bytes, originalName, maxBytes);
        // Serialise uploads per NGO so two parallel uploads cannot both pass the quota check.
        jdbc.query("select pg_advisory_xact_lock(hashtext(?))", rs -> { }, "storage:" + ngoId);
        DocumentRules.checkQuota(usage(ngoId).usedBytes(), f.bytes().length, limitMb(ngoId));
        UUID id = UUID.randomUUID();
        LocalDate now = today();
        String key = "ngos/" + ngoId + "/library/" + now.getYear() + "/" + String.format("%02d", now.getMonthValue()) + "/" + id + "." + f.extension();
        storage.put(key, f.bytes(), f.contentType());
        jdbc.update("insert into documents(id, ngo_id, title, description, category, tags, file_type, content_type, size_bytes, sha256, "
                        + "original_name, storage_key, source, visibility, link_type, link_id, link_parent_id, link_label, document_date, "
                        + "expires_on, uploaded_by) values (?,?,?,?,?,string_to_array(?, chr(31)),?,?,?,?,?,?,'UPLOAD',?,?,?,?,?,?,?,?)",
                id, ngoId, title, description, category, String.join(String.valueOf(SEP), tags), f.fileType(), f.contentType(),
                (long) f.bytes().length, f.sha256(), originalName == null ? null : limit(originalName, 200), key, visibility,
                in.linkType(), in.linkId(), in.linkParentId(), limit(in.linkLabel(), 200), in.documentDate(), in.expiresOn(), m.userId());
        addUsage(ngoId, f.bytes().length, 1);
        record(ngoId, id, m.userId(), "UPLOADED");
        audit.record(ngoId, m.userId(), "DOCUMENT_UPLOADED", "DOCUMENT", id, Map.of("category", category));
        return find(ngoId, id);
    }

    @Transactional
    public Doc update(UUID ngoId, Membership m, UUID id, UploadInput in, long expected) {
        Doc d = get(ngoId, m, id);
        DocumentRules.checkManage(m, d.rules());
        if (!"ACTIVE".equals(d.status())) throw ApiException.rule("DOCUMENT_DELETED", "Restore the document first");
        String category = DocumentRules.category(in.category());
        String title = DocumentRules.title(in.title(), d.title());
        List<String> tags = DocumentRules.tags(in.tags());
        String visibility = DocumentRules.visibility(in.visibility(), category);
        DocumentRules.checkDates(in.documentDate(), in.expiresOn(), today());
        int n = jdbc.update("update documents set title = ?, description = ?, category = ?, tags = string_to_array(?, chr(31)), visibility = ?, "
                        + "document_date = ?, expires_on = ?, updated_by = ?, updated_at = now(), version = version + 1 "
                        + "where id = ? and ngo_id = ? and version = ?",
                title, limit(in.description(), 1000), category, String.join(String.valueOf(SEP), tags), visibility, in.documentDate(),
                in.expiresOn(), m.userId(), id, ngoId, expected);
        if (n == 0) throw ApiException.conflict("STALE_VERSION", "The document was changed by someone else. Reload.");
        record(ngoId, id, m.userId(), "UPDATED");
        return find(ngoId, id);
    }

    @Transactional
    public Doc delete(UUID ngoId, Membership m, UUID id) {
        Doc d = get(ngoId, m, id);
        DocumentRules.checkManage(m, d.rules());
        if (!"ACTIVE".equals(d.status())) return d;
        LocalDate created = d.createdAt().atZone(INDIA).toLocalDate();
        jdbc.update("update documents set status = 'DELETED', deleted_by = ?, deleted_at = now(), purge_after = ?, updated_at = now(), "
                + "version = version + 1 where id = ?", m.userId(), DocumentRules.purgeAfter(d.category(), d.documentDate(), created, today()), id);
        record(ngoId, id, m.userId(), "DELETED");
        audit.record(ngoId, m.userId(), "DOCUMENT_DELETED", "DOCUMENT", id, Map.of());
        return find(ngoId, id);
    }

    @Transactional
    public Doc restore(UUID ngoId, Membership m, UUID id) {
        Doc d = get(ngoId, m, id);
        DocumentRules.checkManage(m, d.rules());
        if (!"DELETED".equals(d.status())) return d;
        jdbc.update("update documents set status = 'ACTIVE', deleted_by = null, deleted_at = null, purge_after = null, updated_at = now(), "
                + "version = version + 1 where id = ?", id);
        record(ngoId, id, m.userId(), "RESTORED");
        return find(ngoId, id);
    }

    /** Recycle bin: administrators see every deleted upload, members their own. */
    public List<Doc> bin(UUID ngoId, Membership m) {
        String sql = "select " + COLS + " from documents where ngo_id = ? and status = 'DELETED'" + (m.isAdmin() ? "" : " and uploaded_by = ?")
                + " order by deleted_at desc limit 200";
        return m.isAdmin() ? jdbc.query(sql, ROW, ngoId) : jdbc.query(sql, ROW, ngoId, m.userId());
    }

    /** Removes the bytes of deleted uploads whose bin period (or statutory retention) is over. */
    @Scheduled(cron = "${ngo.documents.purge-cron:0 30 1 * * *}", zone = "Asia/Kolkata")
    public void purge() {
        // Files a live CSR link still points to are kept until the link ends (CsrService: frozen as sent).
        for (Doc d : jdbc.query("select " + COLS + " from documents d where status = 'DELETED' and purge_after < ? and source = 'UPLOAD' "
                + "and not exists (select 1 from csr_pack_items i join csr_packs p on p.id = i.pack_id where i.storage_key = d.storage_key "
                + "and p.status = 'SHARED' and p.expires_at > now()) limit 500", ROW, today())) {
            try {
                storage.delete(d.storageKey());
                jdbc.update("update documents set status = 'PURGED', updated_at = now() where id = ? and status = 'DELETED'", d.id());
                addUsage(d.ngoId(), -d.sizeBytes(), -1);
                record(d.ngoId(), d.id(), null, "PURGED");
            } catch (RuntimeException e) {
                log.warn("purge of {} failed: {}", d.id(), e.getMessage());
            }
        }
    }

    // ---------------- files kept by other modules

    /** Idempotent: the same (source, sourceRef) updates the existing entry. */
    @Transactional
    public void registerExternal(External x) {
        String category = DocumentRules.category(x.category());
        String visibility = x.visibility() == null ? DocumentRules.visibility(null, category) : DocumentRules.visibility(x.visibility(), category);
        String ct = x.contentType() == null ? "application/octet-stream" : x.contentType();
        String type = ct.equals("application/pdf") ? "PDF" : ct.startsWith("image/") ? "IMAGE" : "OTHER";
        List<UUID> existing = jdbc.queryForList("select id from documents where source = ? and source_ref = ?", UUID.class, x.source(), x.sourceRef());
        if (!existing.isEmpty()) {
            jdbc.update("update documents set title = ?, category = ?, visibility = ?, required_permission = ?, link_label = ?, document_date = ?, "
                            + "expires_on = ?, status = 'ACTIVE', updated_at = now() where id = ?",
                    limit(x.title(), 200), category, visibility, x.requiredPermission(), limit(x.linkLabel(), 200), x.documentDate(),
                    x.expiresOn(), existing.getFirst());
            return;
        }
        UUID id = UUID.randomUUID();
        jdbc.update("insert into documents(id, ngo_id, title, category, file_type, content_type, size_bytes, original_name, storage_key, "
                        + "source, source_ref, visibility, required_permission, link_type, link_id, link_parent_id, link_label, document_date, "
                        + "expires_on, uploaded_by, created_at) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id, x.ngoId(), DocumentRules.title(x.title(), "Document"), category, type, ct, x.sizeBytes(), limit(x.originalName(), 200),
                x.storageKey(), x.source(), x.sourceRef(), visibility, x.requiredPermission(), x.linkType(), x.linkId(), x.linkParentId(),
                limit(x.linkLabel(), 200), x.documentDate(), x.expiresOn(), x.uploadedBy(),
                java.sql.Timestamp.from(x.createdAt() == null ? Instant.now() : x.createdAt()));
        addUsage(x.ngoId(), x.sizeBytes(), 1);
        record(x.ngoId(), id, x.uploadedBy(), "REGISTERED");
    }

    /** The owning module deleted or replaced its file: it disappears from the vault (the module deleted the bytes). */
    @Transactional
    public void removeExternal(String source, String sourceRef) {
        for (Doc d : jdbc.query("select " + COLS + " from documents where source = ? and source_ref = ? and status <> 'PURGED'", ROW, source, sourceRef)) {
            jdbc.update("update documents set status = 'PURGED', updated_at = now() where id = ?", d.id());
            addUsage(d.ngoId(), -d.sizeBytes(), -1);
            record(d.ngoId(), d.id(), null, "REMOVED_AT_SOURCE");
        }
    }

    /** Files this service writes itself (approved minutes). */
    @Transactional
    public UUID store(UUID ngoId, UUID actor, String source, String sourceRef, String title, String category, String visibility,
                      byte[] pdf, String linkType, UUID linkId, String linkLabel, LocalDate documentDate) {
        UUID id = UUID.randomUUID();
        String key = "ngos/" + ngoId + "/library/system/" + id + ".pdf";
        storage.put(key, pdf, "application/pdf");
        jdbc.update("insert into documents(id, ngo_id, title, category, file_type, content_type, size_bytes, sha256, storage_key, source, "
                        + "source_ref, visibility, link_type, link_id, link_label, document_date, uploaded_by) "
                        + "values (?,?,?,?,'PDF','application/pdf',?,?,?,?,?,?,?,?,?,?,?)",
                id, ngoId, limit(title, 200), category, (long) pdf.length, DocFiles.sha256(pdf), key, source, sourceRef, visibility,
                linkType, linkId, limit(linkLabel, 200), documentDate, actor);
        addUsage(ngoId, pdf.length, 1);
        record(ngoId, id, actor, "REGISTERED");
        return id;
    }

    private void addUsage(UUID ngoId, long bytes, int files) {
        jdbc.update("insert into storage_usage(ngo_id, bytes, files) values (?, greatest(?, 0), greatest(?, 0)) on conflict (ngo_id) do update "
                + "set bytes = greatest(storage_usage.bytes + ?, 0), files = greatest(storage_usage.files + ?, 0), updated_at = now()",
                ngoId, bytes, files, bytes, files);
    }

    static String limit(String s, int max) {
        if (s == null) return null;
        String t = s.strip();
        if (t.isEmpty()) return null;
        return t.length() > max ? t.substring(0, max) : t;
    }

    static String stripExtension(String name) {
        if (name == null) return null;
        String n = name.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1);
        int i = n.lastIndexOf('.');
        return (i > 0 ? n.substring(0, i) : n).replace('_', ' ').replace('-', ' ');
    }

    public byte[] read(Doc d) {
        try (InputStream in = open(d)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("Could not read document " + d.id(), e);
        }
    }
}
