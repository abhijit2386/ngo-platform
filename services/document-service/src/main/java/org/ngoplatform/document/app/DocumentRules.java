package org.ngoplatform.document.app;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.ngoplatform.commons.error.ApiException;
import org.ngoplatform.commons.security.Membership;

/**
 * Document vault rules (owner decisions 2026-11-01):
 * <ul>
 *   <li>Administrators see every document of their NGO.</li>
 *   <li>Other members see what they uploaded plus documents shared with the team (TEAM), and for files kept by
 *       another module (e.g. expense bills) only when they hold that module's permission.</li>
 *   <li>Search by name, category, type, dates, tags, uploader and linked record - not by file content.</li>
 *   <li>Deleted documents stay in a recycle bin for 30 days; finance and tax records are kept at least 8 years.</li>
 * </ul>
 */
public final class DocumentRules {
    private DocumentRules() {}

    public static final List<String> CATEGORIES = List.of("LEGAL_REGISTRATION", "TAX_COMPLIANCE", "FINANCE", "CAMPAIGN",
            "PROJECT", "AGREEMENT", "MEETING", "HR", "PHOTOS_REPORTS", "RECEIPT", "CSR", "OTHER");
    /** Confidential by default: shared only with administrators unless the uploader chooses the team. */
    public static final Set<String> CONFIDENTIAL = Set.of("LEGAL_REGISTRATION", "HR", "MEETING", "AGREEMENT");
    /** Statutory records: kept at least {@link #RETENTION_YEARS} years from the document date, even when deleted. */
    public static final Set<String> RETAINED = Set.of("FINANCE", "TAX_COMPLIANCE", "RECEIPT", "CSR");
    public static final int RETENTION_YEARS = 8;
    public static final int BIN_DAYS = 30;
    public static final Set<String> VISIBILITIES = Set.of("TEAM", "RESTRICTED");
    public static final Set<String> FILE_TYPES = Set.of("PDF", "IMAGE", "WORD", "EXCEL", "SLIDES", "OTHER");
    public static final Set<String> LINK_TYPES = Set.of("CAMPAIGN", "EXPENSE", "PROJECT", "MEETING", "NGO");
    public static final int MAX_TAGS = 10;
    static final Pattern TAG = Pattern.compile("^[\\p{L}\\p{N}][\\p{L}\\p{N} &._/-]{0,29}$");

    /** Default storage per NGO when the plan does not say (MB). */
    public static final int DEFAULT_STORAGE_MB = 2048;

    public record Doc(UUID uploadedBy, String visibility, String requiredPermission, String source) {}

    public static boolean canSee(Membership m, Doc d) {
        if (m.isAdmin()) return true;
        if (d.uploadedBy() != null && d.uploadedBy().equals(m.userId())) return true;
        return "TEAM".equals(d.visibility()) && (d.requiredPermission() == null || m.can(d.requiredPermission()));
    }

    /** Only uploads are managed in the vault; files of other modules are changed where they were added. */
    public static void checkManage(Membership m, Doc d) {
        if (!"UPLOAD".equals(d.source())) {
            throw ApiException.rule("MANAGED_ELSEWHERE", "This file belongs to another screen (e.g. an expense or the NGO registration). Change it there.");
        }
        if (!m.isAdmin() && (d.uploadedBy() == null || !d.uploadedBy().equals(m.userId()))) {
            throw ApiException.forbidden("NOT_YOUR_DOCUMENT", "Only the person who uploaded this document or an administrator can change it");
        }
    }

    public static String category(String c) {
        if (c == null || !CATEGORIES.contains(c)) throw ApiException.badRequest("INVALID_CATEGORY", "Choose a category");
        return c;
    }

    public static String visibility(String v, String category) {
        if (v == null || v.isBlank()) return CONFIDENTIAL.contains(category) ? "RESTRICTED" : "TEAM";
        if (!VISIBILITIES.contains(v)) throw ApiException.badRequest("INVALID_VISIBILITY", "Choose who can see this document");
        return v;
    }

    public static String title(String t, String fallback) {
        String s = t == null || t.isBlank() ? fallback : t;
        s = s == null ? "" : s.strip().replaceAll("\\s+", " ");
        if (s.length() < 2 || s.length() > 200) throw ApiException.badRequest("INVALID_TITLE", "Give the document a name (2-200 characters)");
        return s;
    }

    /** Tags: trimmed, lower-cased, unique, at most 10 of up to 30 characters. */
    public static List<String> tags(List<String> raw) {
        if (raw == null) return List.of();
        Set<String> out = new LinkedHashSet<>();
        for (String t : raw) {
            if (t == null) continue;
            String s = t.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
            if (s.startsWith("#")) s = s.substring(1);
            if (s.isEmpty()) continue;
            if (!TAG.matcher(s).matches()) throw ApiException.badRequest("INVALID_TAG", "Tags are 1-30 letters or numbers: \"" + t + "\"");
            out.add(s);
        }
        if (out.size() > MAX_TAGS) throw ApiException.badRequest("TOO_MANY_TAGS", "At most " + MAX_TAGS + " tags");
        return new ArrayList<>(out);
    }

    public static void checkDates(LocalDate documentDate, LocalDate expiresOn, LocalDate today) {
        if (documentDate != null && documentDate.isAfter(today)) throw ApiException.badRequest("DATE_IN_FUTURE", "The document date cannot be in the future");
        if (documentDate != null && expiresOn != null && expiresOn.isBefore(documentDate)) {
            throw ApiException.badRequest("INVALID_EXPIRY", "The expiry date is before the document date");
        }
    }

    /** When a deleted document may be removed for good: 30 days, or the statutory retention for finance / tax records. */
    public static LocalDate purgeAfter(String category, LocalDate documentDate, LocalDate createdOn, LocalDate today) {
        LocalDate bin = today.plusDays(BIN_DAYS);
        if (!RETAINED.contains(category)) return bin;
        LocalDate base = documentDate != null ? documentDate : createdOn;
        LocalDate keep = base.plusYears(RETENTION_YEARS);
        return keep.isAfter(bin) ? keep : bin;
    }

    /** Uploads beyond the plan's storage are refused (limit in MB; negative = unlimited). */
    public static void checkQuota(long usedBytes, long addBytes, int limitMb) {
        if (limitMb < 0) return;
        long limit = limitMb * 1024L * 1024L;
        if (usedBytes + addBytes > limit) {
            throw ApiException.entitlement("STORAGE_MB_MAX", "Your plan's document storage (" + human(limit) + ") is full. Delete old files or upgrade.");
        }
    }

    public static String human(long bytes) {
        if (bytes >= 1024L * 1024 * 1024) return String.format(Locale.ROOT, "%.1f GB", bytes / (1024.0 * 1024 * 1024));
        if (bytes >= 1024L * 1024) return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024));
        return Math.max(1, bytes / 1024) + " KB";
    }
}
