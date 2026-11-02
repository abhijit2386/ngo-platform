package org.ngoplatform.campaign.app;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.ngoplatform.commons.error.ApiException;
import org.ngoplatform.commons.money.Money;

/**
 * Rules of the utilisation certificate (CSR, owner decision 2026-11-02). Pure functions: input checks, the CA's
 * identifiers (ICAI membership number, firm registration number, UDIN), UC numbering, figures by budget head and the
 * certificate wording.
 */
public final class UcRules {
    private UcRules() {}

    public static final int CA_LINK_DAYS = 14;
    static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    // ------------------------------------------------------------------ inputs

    public static String funder(String s) { return text(s, 2, 160, "FUNDER", "Enter the company or foundation that gave the grant"); }

    public static String optional(String s, int max, String code) {
        if (s == null || s.isBlank()) return null;
        String t = s.strip();
        if (t.length() > max) throw ApiException.badRequest("INVALID_" + code, "Too long (up to " + max + " characters)");
        return t;
    }

    public static BigDecimal grant(BigDecimal v) {
        if (v == null || v.signum() <= 0 || v.stripTrailingZeros().scale() > 2 || v.compareTo(new BigDecimal("1000000000")) > 0) {
            throw ApiException.badRequest("INVALID_GRANT", "Enter the grant amount in rupees (up to 2 decimals)");
        }
        return Money.round(v);
    }

    /** The period must lie within the campaign and not in the future; the grant date not in the future. */
    public static void period(LocalDate from, LocalDate to, LocalDate received, LocalDate campaignStart, LocalDate today) {
        if (from == null || to == null || received == null) throw ApiException.badRequest("PERIOD_REQUIRED", "Enter the period and the date the grant was received");
        if (to.isBefore(from)) throw ApiException.badRequest("INVALID_PERIOD", "The period ends before it starts");
        if (to.isAfter(today)) throw ApiException.badRequest("PERIOD_IN_FUTURE", "The period cannot end in the future");
        if (received.isAfter(today)) throw ApiException.badRequest("DATE_IN_FUTURE", "The grant date cannot be in the future");
        if (campaignStart != null && to.isBefore(campaignStart)) {
            throw ApiException.badRequest("PERIOD_BEFORE_CAMPAIGN", "The period ends before the campaign started (" + DATE.format(campaignStart) + ")");
        }
    }

    public static String fiscalYear(LocalDate d) {
        int start = d.getMonthValue() >= 4 ? d.getYear() : d.getYear() - 1;
        return start + "-" + String.format("%02d", (start + 1) % 100);
    }

    /** UC/2026-27/001 */
    public static String number(String fy, long n) { return "UC/" + fy + "/" + String.format("%03d", n); }

    // ------------------------------------------------------------------ the chartered accountant

    private static final Pattern MEMBERSHIP = Pattern.compile("^\\d{6}$");
    private static final Pattern FRN = Pattern.compile("^\\d{6}[A-Z]$");
    private static final Pattern UDIN = Pattern.compile("^\\d{2}(\\d{6})[A-Z0-9]{10}$");

    public record Ca(String name, String membershipNo, String firm, String firmRegNo, String udin, String place) {}

    /**
     * ICAI identifiers: membership number (6 digits), firm registration number (6 digits + zone letter, optional for
     * a CA practising alone), UDIN (18 characters: year, the same membership number, then 10 letters/digits).
     */
    public static Ca ca(String name, String membershipNo, String firm, String firmRegNo, String udin, String place, LocalDate today) {
        String n = text(name, 3, 120, "CA_NAME", "Enter your name as registered with ICAI");
        String m = membershipNo == null ? "" : membershipNo.replaceAll("\\s", "");
        if (!MEMBERSHIP.matcher(m).matches()) throw ApiException.badRequest("INVALID_MEMBERSHIP_NO", "The ICAI membership number has 6 digits");
        String f = optional(firm, 160, "FIRM");
        String frn = firmRegNo == null || firmRegNo.isBlank() ? null : firmRegNo.replaceAll("\\s", "").toUpperCase(Locale.ROOT);
        if (frn != null && !FRN.matcher(frn).matches()) throw ApiException.badRequest("INVALID_FRN", "The firm registration number is 6 digits and a letter, e.g. 012345W");
        String u = udin == null ? "" : udin.replaceAll("\\s", "").toUpperCase(Locale.ROOT);
        var um = UDIN.matcher(u);
        if (!um.matches()) throw ApiException.badRequest("INVALID_UDIN", "The UDIN has 18 characters (generate it on udin.icai.org)");
        if (!um.group(1).equals(m)) throw ApiException.badRequest("UDIN_MEMBERSHIP_MISMATCH", "The UDIN does not belong to this membership number");
        int yy = Integer.parseInt(u.substring(0, 2));
        if (yy > today.getYear() % 100 || yy < (today.getYear() - 1) % 100) {
            throw ApiException.badRequest("UDIN_YEAR", "This UDIN was not generated this year or last year");
        }
        return new Ca(n, m, f, frn, u, optional(place, 80, "PLACE"));
    }

    // ------------------------------------------------------------------ figures

    public record Line(String voucherNo, LocalDate date, String head, String description, String vendor, BigDecimal amount, int bills) {}
    public record Head(String head, BigDecimal budget, BigDecimal spent, int count) {}
    public record Figures(BigDecimal grant, BigDecimal spent, BigDecimal utilised, BigDecimal unspent, BigDecimal ownFunds,
                          List<Head> heads, List<Line> lines, int withoutBill, BigDecimal receivedInPeriod) {}

    /**
     * Expenses of the period grouped by budget head (budget lines in their order, then heads without a budget line).
     * Utilised = what was spent, up to the grant; anything above the grant was met from the NGO's own funds.
     */
    public static Figures figures(BigDecimal grant, Map<String, BigDecimal> budget, List<Line> lines, BigDecimal receivedInPeriod) {
        Map<String, Head> heads = new LinkedHashMap<>();
        budget.forEach((h, b) -> heads.put(h.toLowerCase(Locale.ROOT), new Head(h, Money.round(b), Money.ZERO, 0)));
        BigDecimal spent = Money.ZERO;
        int noBill = 0;
        for (Line l : lines) {
            spent = spent.add(l.amount());
            if (l.bills() == 0) noBill++;
            String key = l.head().toLowerCase(Locale.ROOT);
            Head h = heads.getOrDefault(key, new Head(l.head(), null, Money.ZERO, 0));
            heads.put(key, new Head(h.head(), h.budget(), h.spent().add(l.amount()), h.count() + 1));
        }
        List<Head> used = new ArrayList<>(heads.values().stream().filter(h -> h.count() > 0 || h.budget() != null && h.budget().signum() > 0).toList());
        BigDecimal utilised = spent.min(grant);
        BigDecimal unspent = grant.subtract(utilised);
        BigDecimal own = spent.subtract(grant).max(Money.ZERO);
        return new Figures(Money.round(grant), Money.round(spent), Money.round(utilised), Money.round(unspent), Money.round(own),
                used, lines, noBill, Money.round(receivedInPeriod));
    }

    // ------------------------------------------------------------------ wording

    public static String inr(BigDecimal v) { return "Rs " + group(v, true); }

    /** Indian digit grouping (12,34,567.00); the JDK's en-IN format groups in thousands only. */
    public static String group(BigDecimal v, boolean decimals) {
        BigDecimal r = v.setScale(decimals ? 2 : 0, java.math.RoundingMode.HALF_UP);
        String plain = r.abs().toPlainString();
        String whole = plain.contains(".") ? plain.substring(0, plain.indexOf('.')) : plain;
        String frac = plain.contains(".") ? plain.substring(plain.indexOf('.')) : "";
        StringBuilder b = new StringBuilder();
        if (whole.length() <= 3) b.append(whole);
        else {
            String head = whole.substring(0, whole.length() - 3);
            for (int i = 0; i < head.length(); i++) {
                if (i > 0 && (head.length() - i) % 2 == 0) b.append(',');
                b.append(head.charAt(i));
            }
            b.append(',').append(whole.substring(whole.length() - 3));
        }
        return (r.signum() < 0 ? "-" : "") + b + frac;
    }

    /** The certificate paragraph signed by the NGO and certified by the CA (standard UC wording, adapted to CSR). */
    public static String statement(String ngo, String funder, String reference, String project, LocalDate from, LocalDate to,
                                   LocalDate received, Figures f) {
        StringBuilder s = new StringBuilder();
        s.append("Certified that a CSR grant of ").append(inr(f.grant())).append(" was received by ").append(ngo).append(" from ")
                .append(funder).append(reference == null ? "" : " (reference " + reference + ")").append(" on ")
                .append(DATE.format(received)).append(" for the project \"").append(project).append("\". ");
        s.append("During the period ").append(DATE.format(from)).append(" to ").append(DATE.format(to)).append(", a sum of ")
                .append(inr(f.utilised())).append(" out of this grant has been utilised for the purpose for which it was given, ")
                .append("as detailed in the statement and annexure");
        if (f.unspent().signum() > 0) s.append(", and the balance of ").append(inr(f.unspent())).append(" remains unutilised at the end of the period");
        s.append(". ");
        if (f.ownFunds().signum() > 0) {
            s.append("Expenditure of ").append(inr(f.ownFunds())).append(" above the grant was met from the organisation's own funds. ");
        }
        s.append("The expenditure is supported by vouchers recorded and approved in the organisation's books of account.");
        return s.toString();
    }

    public static final String CA_DECLARATION = "I have examined the books of account, vouchers and records of the organisation for "
            + "the period stated and certify that, to the best of my knowledge and according to the information and explanations "
            + "given to me, the statement above is true and correct.";

    // ------------------------------------------------------------------ helpers

    private static String text(String s, int min, int max, String code, String msg) {
        String t = s == null ? "" : s.strip();
        if (t.length() < min || t.length() > max) throw ApiException.badRequest("INVALID_" + code, msg);
        return t;
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    /** 128 random bits, URL-safe (22 characters): the CA's link cannot be guessed. */
    public static String newCode() {
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    public static boolean validCode(String code) { return code != null && code.matches("^[A-Za-z0-9_-]{22}$"); }
}
