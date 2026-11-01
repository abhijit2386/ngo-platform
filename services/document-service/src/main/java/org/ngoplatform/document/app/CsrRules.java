package org.ngoplatform.document.app;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.ngoplatform.commons.error.ApiException;

/**
 * Rules of the CSR compliance pack (owner decision 2026-11-02, Enterprise). Companies funding CSR projects in India
 * ask for the same papers every time; {@link #KINDS} lists them in the order a CSR team reads them.
 */
public final class CsrRules {
    private CsrRules() {}

    /** label = English name shown to the company; required = the pack is "not ready" without it. */
    public record Kind(String code, String label, boolean required) {}

    public static final List<Kind> KINDS = List.of(
            new Kind("REGISTRATION", "Registration certificate / trust deed", true),
            new Kind("PAN", "PAN of the organisation", true),
            new Kind("REG_12A", "12A registration", true),
            new Kind("REG_80G", "80G registration", true),
            new Kind("CSR1", "CSR-1 registration (MCA)", true),
            new Kind("AUDITED_ACCOUNTS", "Audited financial statements", true),
            new Kind("ANNUAL_REPORT", "Annual report", false),
            new Kind("BOARD_LIST", "Board / trustee list", false),
            new Kind("FCRA", "FCRA registration", false),
            new Kind("UTILISATION", "Utilisation certificate", false),
            new Kind("PROPOSAL", "Project proposal", false),
            new Kind("OTHER", "Other document", false));

    public static final int MAX_ITEMS = 30;
    public static final int DEFAULT_VALID_DAYS = 30;
    public static final int MAX_VALID_DAYS = 90;
    /** Documents expiring within this many days are flagged before sharing. */
    public static final int EXPIRY_WARNING_DAYS = 30;

    public static Kind kind(String code) {
        return KINDS.stream().filter(k -> k.code().equals(code)).findFirst()
                .orElseThrow(() -> ApiException.badRequest("INVALID_KIND", "Choose what this document is"));
    }

    // ------------------------------------------------------------------ suggestions from the vault

    /** Words in a vault title or tag that identify a kind (registration documents arrive titled "80G certificate" ...). */
    private static final Map<String, Pattern> MATCH = new LinkedHashMap<>();
    static {
        MATCH.put("CSR1", Pattern.compile("\\bcsr[\\s-]?1\\b"));
        MATCH.put("REG_80G", Pattern.compile("\\b80\\s?g\\b"));
        MATCH.put("REG_12A", Pattern.compile("\\b12\\s?a(b)?\\b"));
        MATCH.put("FCRA", Pattern.compile("\\bfcra\\b"));
        MATCH.put("PAN", Pattern.compile("\\bpan\\b"));
        MATCH.put("AUDITED_ACCOUNTS", Pattern.compile("\\baudit(ed)?\\b|balance sheet|income (and|&) expenditure"));
        MATCH.put("ANNUAL_REPORT", Pattern.compile("\\bannual report\\b"));
        MATCH.put("BOARD_LIST", Pattern.compile("\\b(board|trustees?|governing body)\\b"));
        MATCH.put("UTILISATION", Pattern.compile("\\butili[sz]ation\\b"));
        MATCH.put("PROPOSAL", Pattern.compile("\\bproposal\\b"));
        MATCH.put("REGISTRATION", Pattern.compile("registration certificate|trust deed|\\bmoa\\b|memorandum|society registration"));
    }

    /** The kind a vault document most likely proves, or null. The first match wins (CSR-1 before PAN etc.). */
    public static String guessKind(String title, List<String> tags) {
        String text = ((title == null ? "" : title) + " " + String.join(" ", tags == null ? List.of() : tags)).toLowerCase(Locale.ROOT);
        for (Map.Entry<String, Pattern> e : MATCH.entrySet()) {
            if (e.getValue().matcher(text).find()) return e.getKey();
        }
        return null;
    }

    // ------------------------------------------------------------------ readiness

    /** One document of a pack as it is now in the vault. */
    public record ItemState(String kind, String title, String documentStatus, LocalDate expiresOn) {}

    public record Readiness(List<String> errors, List<String> warnings, List<Map<String, Object>> checklist) {
        public boolean ready() { return errors.isEmpty() && warnings.isEmpty(); }
    }

    /**
     * errors block sharing (a deleted or expired document, an empty pack); warnings are shown and must be accepted
     * (a required paper missing, a document expiring soon).
     */
    public static Readiness readiness(List<ItemState> items, LocalDate today) {
        List<String> errors = new ArrayList<>(), warnings = new ArrayList<>();
        if (items.isEmpty()) errors.add("EMPTY: add at least one document");
        for (ItemState i : items) {
            if (!"ACTIVE".equals(i.documentStatus())) errors.add("DOCUMENT_REMOVED: " + i.title());
            else if (i.expiresOn() != null && i.expiresOn().isBefore(today)) errors.add("DOCUMENT_EXPIRED: " + i.title() + " (" + i.expiresOn() + ")");
            else if (i.expiresOn() != null && !i.expiresOn().isAfter(today.plusDays(EXPIRY_WARNING_DAYS))) {
                warnings.add("EXPIRES_SOON: " + i.title() + " (" + i.expiresOn() + ")");
            }
        }
        List<Map<String, Object>> checklist = new ArrayList<>();
        for (Kind k : KINDS) {
            if ("OTHER".equals(k.code())) continue;
            long n = items.stream().filter(i -> k.code().equals(i.kind())).count();
            if (k.required() && n == 0) warnings.add("MISSING: " + k.code());
            if (k.required() || n > 0) {
                checklist.add(Map.of("kind", k.code(), "label", k.label(), "required", k.required(), "count", n));
            }
        }
        return new Readiness(errors, warnings, checklist);
    }

    // ------------------------------------------------------------------ input checks

    public static String title(String s) { return text(s, 3, 120, "TITLE", "Give the pack a name (3-120 characters)"); }
    public static String recipient(String s) { return text(s, 2, 160, "RECIPIENT", "Enter the company's name"); }

    public static String message(String s) {
        if (s == null || s.isBlank()) return null;
        if (s.strip().length() > 1000) throw ApiException.badRequest("MESSAGE_TOO_LONG", "The note can have up to 1000 characters");
        return s.strip();
    }

    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]{1,64}@[^\\s@]+\\.[^\\s@]{2,}$");

    public static String email(String s) {
        if (s == null || s.isBlank()) return null;
        String e = s.strip().toLowerCase(Locale.ROOT);
        if (e.length() > 200 || !EMAIL.matcher(e).matches()) throw ApiException.badRequest("INVALID_EMAIL", "Enter a valid e-mail address");
        return e;
    }

    public static int validDays(Integer d) {
        int v = d == null ? DEFAULT_VALID_DAYS : d;
        if (v < 1 || v > MAX_VALID_DAYS) throw ApiException.badRequest("INVALID_VALIDITY", "The link can stay open for 1 to 90 days");
        return v;
    }

    private static String text(String s, int min, int max, String code, String msg) {
        String t = s == null ? "" : s.strip();
        if (t.length() < min || t.length() > max) throw ApiException.badRequest("INVALID_" + code, msg);
        return t;
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    /** 128 random bits, URL-safe (22 characters): the company's link cannot be guessed. */
    public static String newCode() {
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    public static boolean validCode(String code) { return code != null && code.matches("^[A-Za-z0-9_-]{22}$"); }
}
