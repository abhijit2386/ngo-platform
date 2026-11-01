package org.ngoplatform.receipt.app;

import java.io.IOException;
import java.io.InputStream;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.ngoplatform.commons.audit.AuditLog;
import org.ngoplatform.commons.error.ApiException;
import org.ngoplatform.commons.storage.ObjectStorage;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Receipt settings (prefix, 12A number, thank-you line) and signatories with signature images. Admins only edit. */
@Service
public class ReceiptSettings {
    public record Settings(UUID ngoId, String prefix, String reg12aNumber, String thankYouNote, Instant updatedAt, long version,
                           boolean saved) {}
    public record Signatory(UUID id, String name, String designation, boolean hasSignature, int position, boolean active,
                            Instant updatedAt, long version) {}

    private final JdbcTemplate jdbc;
    private final ObjectStorage storage;
    private final Facts facts;
    private final AuditLog audit;

    public ReceiptSettings(JdbcTemplate jdbc, ObjectStorage storage, Facts facts, AuditLog audit) {
        this.jdbc = jdbc; this.storage = storage; this.facts = facts; this.audit = audit;
    }

    /** Saved settings, or defaults (prefix from the NGO's name) until an admin saves them. */
    public Settings get(UUID ngoId) {
        try {
            return jdbc.queryForObject("select * from receipt_settings where ngo_id = ?", (rs, i) -> new Settings(ngoId,
                    rs.getString("prefix"), rs.getString("reg_12a_number"), rs.getString("thank_you_note"),
                    rs.getTimestamp("updated_at").toInstant(), rs.getLong("version"), true), ngoId);
        } catch (EmptyResultDataAccessException e) {
            String name = null;
            try { Object n = facts.ngo(ngoId).get("displayName"); name = n == null ? null : n.toString(); } catch (RuntimeException ignored) { }
            return new Settings(ngoId, ReceiptRules.defaultPrefix(name), null,
                    "Thank you for your generous support.", null, -1, false);
        }
    }

    @Transactional
    public Settings save(UUID ngoId, UUID actor, String prefix, String reg12a, String thankYou, long expectedVersion) {
        String p = ReceiptRules.prefix(prefix);
        String r = ReceiptRules.optional(reg12a, 60, "INVALID_12A", "The 12A registration number can be at most 60 characters");
        String t = ReceiptRules.optional(thankYou, 200, "NOTE_TOO_LONG", "The thank-you line can be at most 200 characters");
        int n;
        if (expectedVersion < 0) {
            n = jdbc.update("insert into receipt_settings(ngo_id, prefix, reg_12a_number, thank_you_note, updated_by) values (?,?,?,?,?) "
                    + "on conflict do nothing", ngoId, p, r, t, actor);
        } else {
            n = jdbc.update("update receipt_settings set prefix = ?, reg_12a_number = ?, thank_you_note = ?, updated_by = ?, "
                    + "updated_at = now(), version = version + 1 where ngo_id = ? and version = ?", p, r, t, actor, ngoId, expectedVersion);
        }
        if (n == 0) throw ApiException.conflict("STALE_VERSION", "Someone else changed the settings. Reload and try again.");
        audit.record(ngoId, actor, "RECEIPT_SETTINGS_UPDATED", "RECEIPT_SETTINGS", ngoId, Map.of("prefix", p));
        return get(ngoId);
    }

    // ------------------------------------------------------------------ signatories

    private static Signatory signatory(ResultSet rs, int i) throws SQLException {
        return new Signatory(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("designation"),
                rs.getString("signature_key") != null, rs.getInt("position"), rs.getBoolean("active"),
                rs.getTimestamp("updated_at").toInstant(), rs.getLong("version"));
    }

    public List<Signatory> signatories(UUID ngoId) {
        return jdbc.query("select * from receipt_signatories where ngo_id = ? and active order by position, created_at",
                ReceiptSettings::signatory, ngoId);
    }

    private Signatory load(UUID ngoId, UUID id) {
        List<Signatory> l = jdbc.query("select * from receipt_signatories where id = ? and ngo_id = ? and active",
                ReceiptSettings::signatory, id, ngoId);
        if (l.isEmpty()) throw ApiException.notFound("Signatory");
        return l.getFirst();
    }

    @Transactional
    public Signatory add(UUID ngoId, UUID actor, String name, String designation) {
        jdbc.queryForObject("select pg_advisory_xact_lock(hashtext(?))", Object.class, "signatories:" + ngoId);
        Integer count = jdbc.queryForObject("select count(*) from receipt_signatories where ngo_id = ? and active", Integer.class, ngoId);
        if (count != null && count >= ReceiptRules.MAX_SIGNATORIES) {
            throw ApiException.rule("TOO_MANY_SIGNATORIES", "A receipt has room for " + ReceiptRules.MAX_SIGNATORIES + " signatories");
        }
        UUID id = UUID.randomUUID();
        jdbc.update("insert into receipt_signatories(id, ngo_id, name, designation, position, created_by) values (?,?,?,?,?,?)",
                id, ngoId, name(name), designation(designation), count == null ? 0 : count, actor);
        audit.record(ngoId, actor, "RECEIPT_SIGNATORY_ADDED", "RECEIPT_SIGNATORY", id, Map.of());
        return load(ngoId, id);
    }

    @Transactional
    public Signatory update(UUID ngoId, UUID actor, UUID id, String name, String designation, Integer position, long expectedVersion) {
        load(ngoId, id);
        int n = jdbc.update("update receipt_signatories set name = ?, designation = ?, position = coalesce(?, position), updated_at = now(), "
                + "version = version + 1 where id = ? and ngo_id = ? and version = ?", name(name), designation(designation), position, id, ngoId,
                expectedVersion);
        if (n == 0) throw ApiException.conflict("STALE_VERSION", "Someone else changed this signatory. Reload and try again.");
        audit.record(ngoId, actor, "RECEIPT_SIGNATORY_UPDATED", "RECEIPT_SIGNATORY", id, Map.of());
        return load(ngoId, id);
    }

    /** Removed signatories stop appearing on new receipts; issued receipts keep the signature they were printed with. */
    @Transactional
    public void remove(UUID ngoId, UUID actor, UUID id) {
        load(ngoId, id);
        jdbc.update("update receipt_signatories set active = false, updated_at = now(), version = version + 1 where id = ?", id);
        audit.record(ngoId, actor, "RECEIPT_SIGNATORY_REMOVED", "RECEIPT_SIGNATORY", id, Map.of());
    }

    @Transactional
    public Signatory uploadSignature(UUID ngoId, UUID actor, UUID id, byte[] data) {
        load(ngoId, id);
        byte[] png = Images.signature(data);
        String key = "ngos/" + ngoId + "/receipts/signatures/" + id + "-" + UUID.randomUUID() + ".png";
        storage.put(key, png, "image/png");
        jdbc.update("update receipt_signatories set signature_key = ?, updated_at = now(), version = version + 1 where id = ?", key, id);
        audit.record(ngoId, actor, "RECEIPT_SIGNATURE_UPLOADED", "RECEIPT_SIGNATORY", id, Map.of());
        return load(ngoId, id);
    }

    @Transactional
    public Signatory removeSignature(UUID ngoId, UUID actor, UUID id) {
        load(ngoId, id);
        jdbc.update("update receipt_signatories set signature_key = null, updated_at = now(), version = version + 1 where id = ?", id);
        audit.record(ngoId, actor, "RECEIPT_SIGNATURE_REMOVED", "RECEIPT_SIGNATORY", id, Map.of());
        return load(ngoId, id);
    }

    public byte[] signatureImage(UUID ngoId, UUID id) {
        load(ngoId, id);
        String key = jdbc.queryForObject("select signature_key from receipt_signatories where id = ?", String.class, id);
        if (key == null) throw ApiException.notFound("Signature");
        return read(key);
    }

    /** Active signatories with their images, for printing. */
    public List<PdfRenderer.Signatory> forPrinting(UUID ngoId) {
        List<PdfRenderer.Signatory> out = new ArrayList<>();
        jdbc.query("select name, designation, signature_key from receipt_signatories where ngo_id = ? and active order by position, created_at",
                rs -> {
                    String key = rs.getString("signature_key");
                    out.add(new PdfRenderer.Signatory(rs.getString("name"), rs.getString("designation"), key == null ? null : read(key)));
                }, ngoId);
        return out;
    }

    private byte[] read(String key) {
        try (InputStream in = storage.get(key)) { return in.readAllBytes(); } catch (IOException e) { throw new IllegalStateException(e); }
    }

    static String name(String v) {
        return ReceiptRules.required(v, 2, 80, "INVALID_NAME", "Enter the signatory's name (2-80 characters)");
    }

    static String designation(String v) {
        return ReceiptRules.required(v, 2, 60, "INVALID_DESIGNATION", "Enter the designation, for example Chairman (2-60 characters)");
    }
}
