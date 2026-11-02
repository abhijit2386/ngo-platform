package org.ngoplatform.receipt.app;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.Locale;
import org.ngoplatform.commons.error.ApiException;

/** Receipt rules without I/O (unit-tested). */
public final class ReceiptRules {
    private ReceiptRules() {}

    public static final int MAX_SIGNATORIES = 3;
    private static final SecureRandom RANDOM = new SecureRandom();
    /** No 0/O/1/I/L: codes are sometimes typed from a printed receipt. */
    private static final char[] CODE_CHARS = "ABCDEFGHJKMNPQRSTUVWXYZ23456789".toCharArray();

    /** Indian financial year of a date: 1 Apr 2026 - 31 Mar 2027 = "2026-27". */
    public static String fiscalYear(LocalDate d) {
        int start = d.getMonthValue() >= 4 ? d.getYear() : d.getYear() - 1;
        return start + "-" + String.format("%02d", (start + 1) % 100);
    }

    /** PREFIX/2026-27/000001 (owner decision 2026-11-01: restarts at 1 every financial year). */
    public static String number(String prefix, String fiscalYear, long n) {
        return prefix + "/" + fiscalYear + "/" + String.format("%06d", n);
    }

    /** Default prefix from the NGO's name: "Asha Seva Foundation" -> "ASF" (2-10 letters/digits). */
    public static String defaultPrefix(String ngoName) {
        StringBuilder b = new StringBuilder();
        if (ngoName != null) {
            for (String w : ngoName.trim().split("[^A-Za-z0-9]+")) {
                if (!w.isEmpty() && b.length() < 6) b.append(Character.toUpperCase(w.charAt(0)));
            }
        }
        if (b.length() < 2) return "RCPT";
        return b.toString();
    }

    public static String prefix(String p) {
        String v = p == null ? "" : p.trim().toUpperCase(Locale.ROOT);
        if (!v.matches("^[A-Z0-9]{2,10}$")) {
            throw ApiException.badRequest("INVALID_PREFIX", "Use 2 to 10 capital letters or digits, for example ASF");
        }
        return v;
    }

    public static String optional(String v, int max, String code, String message) {
        if (v == null || v.isBlank()) return null;
        String t = v.strip().replaceAll("\\s+", " ");
        if (t.length() > max) throw ApiException.badRequest(code, message);
        return t;
    }

    public static String required(String v, int min, int max, String code, String message) {
        String t = v == null ? "" : v.strip().replaceAll("\\s+", " ");
        if (t.length() < min || t.length() > max) throw ApiException.badRequest(code, message);
        return t;
    }

    /** 10-character code printed under the QR code (verification without the app). */
    public static String verifyCode() {
        char[] c = new char[10];
        for (int i = 0; i < c.length; i++) c[i] = CODE_CHARS[RANDOM.nextInt(CODE_CHARS.length)];
        return new String(c);
    }

    /** Indian digit grouping: 1234567.5 -> "12,34,567.50". */
    public static String inr(BigDecimal amount) {
        BigDecimal a = amount.setScale(2, RoundingMode.HALF_UP);
        String s = a.abs().toPlainString();
        String whole = s.substring(0, s.indexOf('.')), frac = s.substring(s.indexOf('.') + 1);
        StringBuilder b = new StringBuilder();
        int n = whole.length();
        if (n <= 3) b.append(whole);
        else {
            String head = whole.substring(0, n - 3), tail = whole.substring(n - 3);
            StringBuilder h = new StringBuilder();
            for (int i = head.length(); i > 0; i -= 2) h.insert(0, head.substring(Math.max(0, i - 2), i)).insert(0, ',');
            b.append(h.substring(1)).append(',').append(tail);
        }
        return (a.signum() < 0 ? "-" : "") + b + "." + frac;
    }

    /** Verification page shows only initials of the donor: "Meera Shah" -> "M*** S***". */
    public static String maskName(String name) {
        if (name == null || name.isBlank()) return "";
        StringBuilder b = new StringBuilder();
        for (String w : name.trim().split("\\s+")) {
            if (b.length() > 0) b.append(' ');
            int cp = w.codePointAt(0);
            b.appendCodePoint(cp).append("***");
        }
        return b.toString();
    }

    /**
     * 80G receipt when the NGO's 80G registration is verified and valid on the donation date (owner rule, 2 Nov 2026:
     * the receipt follows the NGO, not a donor choice; a donor without PAN still gets the 80G receipt).
     */
    public static boolean eligible80g(boolean ngoVerified, LocalDate validUntil, LocalDate donationDate) {
        return ngoVerified && validUntil != null && !validUntil.isBefore(donationDate);
    }

    public static String channelLabel(String channel) {
        if (channel == null) return "";
        return switch (channel) {
            case "ONLINE" -> "Online payment";
            case "CASH" -> "Cash";
            case "CHEQUE" -> "Cheque";
            case "BANK_TRANSFER" -> "Bank transfer";
            case "UPI_DIRECT" -> "UPI";
            case "IN_KIND_VALUE" -> "In kind";
            default -> channel.replace('_', ' ');
        };
    }

    public static String reason(String r) {
        return required(r, 5, 300, "REASON_REQUIRED", "Give a reason (at least 5 characters)");
    }
}
