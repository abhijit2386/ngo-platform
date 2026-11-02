package org.ngoplatform.campaign.app;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.ngoplatform.commons.error.ApiException;
import org.ngoplatform.commons.money.Money;

/**
 * Rules of CSR funding proposals (CSR 3, owner decision 2026-11-02). Pure functions: input checks, Schedule VII
 * areas, numbering, which status changes are allowed and the pipeline summary.
 */
public final class ProposalRules {
    private ProposalRules() {}

    /**
     * Activities a company may count as CSR (Companies Act 2013, Schedule VII), in short. Item (viii) (government
     * funds) and (ix) (research and incubators) are left out: an NGO does not receive those.
     */
    public static final Map<String, String> SCHEDULE_VII = new LinkedHashMap<>();
    static {
        SCHEDULE_VII.put("HEALTH_HUNGER", "(i) Hunger, poverty, health care, sanitation and safe drinking water");
        SCHEDULE_VII.put("EDUCATION", "(ii) Education, special education, vocational skills and livelihoods");
        SCHEDULE_VII.put("GENDER_EQUALITY", "(iii) Gender equality, women's empowerment, homes for orphans and the elderly");
        SCHEDULE_VII.put("ENVIRONMENT", "(iv) Environment, ecological balance, animal welfare, conservation of natural resources");
        SCHEDULE_VII.put("HERITAGE", "(v) National heritage, art and culture");
        SCHEDULE_VII.put("ARMED_FORCES", "(vi) Armed forces veterans, war widows and their dependants");
        SCHEDULE_VII.put("SPORTS", "(vii) Rural, Paralympic, Olympic and nationally recognised sports");
        SCHEDULE_VII.put("RURAL", "(x) Rural development projects");
        SCHEDULE_VII.put("SLUM", "(xi) Slum area development");
        SCHEDULE_VII.put("DISASTER", "(xii) Disaster management, relief and rehabilitation");
    }

    /** A first guess from the campaign's cause, so the form starts filled in. */
    public static String scheduleFor(String cause) {
        String c = cause == null ? "" : cause.toUpperCase();
        if (c.contains("EDUC") || c.contains("SCHOOL") || c.contains("SKILL")) return "EDUCATION";
        if (c.contains("ENVIRON") || c.contains("ANIMAL") || c.contains("WATER") && c.contains("CONSERV")) return "ENVIRONMENT";
        if (c.contains("WOMEN") || c.contains("GENDER") || c.contains("ELDER") || c.contains("ORPHAN")) return "GENDER_EQUALITY";
        if (c.contains("DISASTER") || c.contains("RELIEF") || c.contains("FLOOD")) return "DISASTER";
        if (c.contains("RURAL") || c.contains("VILLAGE")) return "RURAL";
        if (c.contains("ART") || c.contains("CULTURE") || c.contains("HERITAGE")) return "HERITAGE";
        if (c.contains("SPORT")) return "SPORTS";
        return "HEALTH_HUNGER";
    }

    public static String schedule(String code) {
        if (code == null || !SCHEDULE_VII.containsKey(code)) {
            throw ApiException.badRequest("INVALID_SCHEDULE_VII", "Choose the Schedule VII area the project falls under");
        }
        return code;
    }

    // ------------------------------------------------------------------ inputs

    public static String title(String s) { return text(s, 5, 160, "TITLE", "Give the proposal a title (5-160 characters)"); }

    public static String funder(String s) { return text(s, 2, 160, "FUNDER", "Enter the company or CSR foundation you are writing to"); }

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]{1,64}@[^@\\s]+\\.[A-Za-z]{2,}$");

    public static String email(String s) {
        if (s == null || s.isBlank()) return null;
        String t = s.strip().toLowerCase();
        if (t.length() > 160 || !EMAIL.matcher(t).matches()) throw ApiException.badRequest("INVALID_EMAIL", "Enter a valid e-mail address");
        return t;
    }

    public static BigDecimal ask(BigDecimal v) {
        if (v == null || v.signum() <= 0 || v.stripTrailingZeros().scale() > 2 || v.compareTo(new BigDecimal("1000000000")) > 0) {
            throw ApiException.badRequest("INVALID_AMOUNT", "Enter the amount you are asking for in rupees (up to 2 decimals)");
        }
        return Money.round(v);
    }

    public static int months(Integer m) {
        if (m == null || m < 1 || m > 60) throw ApiException.badRequest("INVALID_DURATION", "Duration: 1 to 60 months");
        return m;
    }

    public static Integer beneficiaries(Integer n) {
        if (n == null) return null;
        if (n < 0 || n > 100_000_000) throw ApiException.badRequest("INVALID_BENEFICIARIES", "Enter the number of people the project will reach");
        return n;
    }

    /** Long sections of the proposal: up to 4000 characters each; empty allowed while a draft. */
    public static String section(String s, String code) {
        String t = s == null ? "" : s.strip().replace("\r\n", "\n");
        if (t.length() > 4000) throw ApiException.badRequest("TOO_LONG_" + code, "Keep each section under 4000 characters");
        return t;
    }

    public static String optional(String s, int max, String code) { return UcRules.optional(s, max, code); }

    /** The compliance-pack link printed in the proposal must be one of our own public pack links. */
    public static String packUrl(String url, String publicBase) {
        if (url == null || url.isBlank()) return null;
        String u = url.strip();
        String prefix = publicBase.replaceAll("/+$", "") + "/api/v1/public/csr/";
        if (!u.startsWith(prefix) || !u.substring(prefix.length()).matches("[A-Za-z0-9_-]{16,40}")) {
            throw ApiException.badRequest("INVALID_PACK_LINK", "Attach a compliance pack shared from Yogdaan");
        }
        return u;
    }

    /** Before sending, the proposal must say what the need is, what it will do and what it will achieve. */
    public static List<String> missingForSending(String need, String objectives, String activities, String outcomes) {
        List<String> missing = new java.util.ArrayList<>();
        if (need == null || need.strip().length() < 30) missing.add("The need (at least 30 characters)");
        if (objectives == null || objectives.strip().length() < 20) missing.add("Objectives (at least 20 characters)");
        if (activities == null || activities.strip().length() < 20) missing.add("Activities (at least 20 characters)");
        if (outcomes == null || outcomes.strip().length() < 20) missing.add("Expected outcomes (at least 20 characters)");
        return missing;
    }

    public static void sentOn(LocalDate sent, LocalDate followUp, LocalDate today) {
        if (sent == null) throw ApiException.badRequest("SENT_ON_REQUIRED", "Enter the date the proposal was sent");
        if (sent.isAfter(today)) throw ApiException.badRequest("DATE_IN_FUTURE", "The sending date cannot be in the future");
        if (sent.isBefore(today.minusDays(365))) throw ApiException.badRequest("DATE_TOO_OLD", "The sending date is more than a year ago");
        followUp(followUp, today);
    }

    public static void followUp(LocalDate followUp, LocalDate today) {
        if (followUp != null && (followUp.isBefore(today) || followUp.isAfter(today.plusDays(366)))) {
            throw ApiException.badRequest("INVALID_FOLLOW_UP", "The follow-up date must be from today up to a year ahead");
        }
    }

    public static void funded(BigDecimal amount, LocalDate on, LocalDate sentOn, LocalDate today) {
        ask(amount);
        if (on == null) throw ApiException.badRequest("FUNDED_ON_REQUIRED", "Enter the date the grant was approved or received");
        if (on.isAfter(today)) throw ApiException.badRequest("DATE_IN_FUTURE", "The date cannot be in the future");
        if (sentOn != null && on.isBefore(sentOn)) throw ApiException.badRequest("BEFORE_SENT", "The grant date is before the proposal was sent");
    }

    /** PR/2026-27/001 */
    public static String number(String fy, long n) { return "PR/" + fy + "/" + String.format("%03d", n); }

    // ------------------------------------------------------------------ status

    public static final List<String> CLOSED = List.of("FUNDED", "DECLINED", "WITHDRAWN");

    /** Outcome after sending: FUNDED or DECLINED (only once sent); WITHDRAWN from a draft or a sent proposal. */
    public static void canClose(String status, String outcome) {
        if (CLOSED.contains(status)) throw ApiException.rule("PROPOSAL_CLOSED", "This proposal is already closed");
        if (("FUNDED".equals(outcome) || "DECLINED".equals(outcome)) && !"SENT".equals(status)) {
            throw ApiException.rule("NOT_SENT", "Mark the proposal as sent first");
        }
        if (!List.of("FUNDED", "DECLINED", "WITHDRAWN").contains(outcome)) throw ApiException.badRequest("INVALID_OUTCOME", "Unknown outcome");
    }

    // ------------------------------------------------------------------ pipeline

    public record Row(String status, BigDecimal ask, BigDecimal funded, LocalDate followUpOn) {}

    public record Summary(int drafts, int open, int funded, int declined, int withdrawn, BigDecimal askedOpen, BigDecimal fundedTotal,
                          Integer successPercent, int followUpsDue) {}

    /** Counts, money asked in open proposals, money won, success rate (funded of decided) and follow-ups due. */
    public static Summary summary(List<Row> rows, LocalDate today) {
        int drafts = 0, open = 0, funded = 0, declined = 0, withdrawn = 0, due = 0;
        BigDecimal asked = Money.ZERO, won = Money.ZERO;
        for (Row r : rows) {
            switch (r.status()) {
                case "DRAFT" -> drafts++;
                case "SENT" -> {
                    open++;
                    asked = asked.add(r.ask());
                    if (r.followUpOn() != null && !r.followUpOn().isAfter(today)) due++;
                }
                case "FUNDED" -> { funded++; won = won.add(r.funded() == null ? Money.ZERO : r.funded()); }
                case "DECLINED" -> declined++;
                default -> withdrawn++;
            }
        }
        Integer rate = funded + declined == 0 ? null
                : BigDecimal.valueOf(funded * 100L).divide(BigDecimal.valueOf(funded + declined), 0, RoundingMode.HALF_UP).intValue();
        return new Summary(drafts, open, funded, declined, withdrawn, Money.round(asked), Money.round(won), rate, due);
    }

    private static String text(String s, int min, int max, String code, String msg) {
        String t = s == null ? "" : s.strip();
        if (t.length() < min || t.length() > max) throw ApiException.badRequest("INVALID_" + code, msg);
        return t;
    }
}
