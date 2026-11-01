package org.ngoplatform.campaign.app;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.ngoplatform.campaign.domain.Campaign;
import org.ngoplatform.commons.error.ApiException;

/**
 * Campaign accounts rules (owner decisions 2026-10-29): every expense is approved by an administrator; staff may pay
 * from a cash advance or their own pocket and the NGO tracks what it owes / is owed; budget vs actual per category.
 */
public final class AccountRules {
    private AccountRules() {}

    public enum Status { SUBMITTED, APPROVED, REJECTED, VOID }

    public static final Set<String> MODES = Set.of("CASH", "UPI", "BANK_TRANSFER", "CHEQUE", "CARD");
    public static final BigDecimal MAX_AMOUNT = new BigDecimal("10000000.00"); // ₹1 crore per bill
    public static final int MAX_ATTACHMENTS = 5;
    public static final int MAX_BUDGET_LINES = 30;
    /** Expenses may be up to this many days before the campaign starts (posters, preparation). */
    public static final int PREP_DAYS = 90;
    public static final String OTHER = "Other";
    /** Suggested categories when a campaign has no budget yet. */
    public static final List<String> DEFAULT_CATEGORIES = List.of("Materials", "Food", "Transport", "Venue", "Printing",
            "Medical", "Honorarium", OTHER);

    static final Pattern GSTIN = Pattern.compile("^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$");
    static final Pattern PAN = Pattern.compile("^[A-Z]{5}[0-9]{4}[A-Z]$");
    static final Pattern PHONE = Pattern.compile("^(\\+91)?[6-9][0-9]{9}$");
    static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    /** Expenses are recorded against a campaign that went live: while it runs and after it closes, until archived. */
    public static void checkCampaignOpenForExpenses(Campaign.Status s) {
        if (s == Campaign.Status.ARCHIVED) throw ApiException.rule("CAMPAIGN_ARCHIVED", "This campaign's accounts are closed (archived)");
        if (s != Campaign.Status.PUBLISHED && s != Campaign.Status.CLOSED) {
            throw ApiException.rule("CAMPAIGN_NOT_LIVE", "Expenses can be added once the campaign is approved");
        }
    }

    public static void validateAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) throw ApiException.badRequest("INVALID_AMOUNT", "Enter an amount above zero");
        if (amount.stripTrailingZeros().scale() > 2) throw ApiException.badRequest("INVALID_AMOUNT", "Use at most 2 decimals (paise)");
        if (amount.compareTo(MAX_AMOUNT) > 0) throw ApiException.badRequest("AMOUNT_TOO_LARGE", "One expense can be at most ₹1 crore");
    }

    public static void validateExpense(BigDecimal amount, LocalDate date, LocalDate today, LocalDate campaignStart,
                                       String mode, String paidBy) {
        validateAmount(amount);
        if (date == null) throw ApiException.badRequest("DATE_REQUIRED", "Enter the bill date");
        if (date.isAfter(today)) throw ApiException.badRequest("DATE_IN_FUTURE", "The bill date cannot be in the future");
        if (date.isBefore(campaignStart.minusDays(PREP_DAYS))) {
            throw ApiException.badRequest("DATE_TOO_EARLY", "The bill date is more than " + PREP_DAYS + " days before the campaign started");
        }
        if (mode == null || !MODES.contains(mode)) throw ApiException.badRequest("INVALID_MODE", "Choose how it was paid");
        if (!"NGO".equals(paidBy) && !"STAFF".equals(paidBy)) throw ApiException.badRequest("INVALID_PAID_BY", "Choose who paid");
    }

    /** Every expense needs a bill (photo / PDF) or a reason why there is none (e.g. auto fare). */
    public static void checkEvidence(int attachments, String noBillReason) {
        if (attachments == 0 && (noBillReason == null || noBillReason.isBlank())) {
            throw ApiException.rule("BILL_REQUIRED", "Attach the bill, or say why there is no bill");
        }
    }

    /** The submitter (or an administrator) edits a pending or rejected expense; editing sends it back for approval. */
    public static void checkEdit(Status s, boolean isSubmitter, boolean isAdmin) {
        if (!isSubmitter && !isAdmin) throw ApiException.forbidden("NOT_YOUR_EXPENSE", "Only the person who added this expense can change it");
        if (s != Status.SUBMITTED && s != Status.REJECTED) {
            throw ApiException.rule("EXPENSE_LOCKED", s == Status.APPROVED ? "Approved expenses cannot be changed. An administrator can void it."
                    : "This expense can no longer be changed");
        }
    }

    public static void checkWithdraw(Status s, boolean isSubmitter) {
        if (!isSubmitter) throw ApiException.forbidden("NOT_YOUR_EXPENSE", "Only the person who added this expense can delete it");
        if (s != Status.SUBMITTED && s != Status.REJECTED) throw ApiException.rule("EXPENSE_LOCKED", "Approved expenses cannot be deleted");
    }

    /**
     * Administrators approve; not their own expense unless they are the NGO's only administrator (then it is marked
     * self-approved on the account sheet).
     */
    public static boolean checkApprove(Status s, boolean isAdmin, boolean isSubmitter, long activeAdmins) {
        if (!isAdmin) throw ApiException.forbidden("ADMIN_REQUIRED", "Only administrators approve expenses");
        if (s != Status.SUBMITTED) throw ApiException.rule("EXPENSE_NOT_PENDING", "Only pending expenses can be approved");
        if (isSubmitter) {
            if (activeAdmins > 1) throw ApiException.rule("SELF_APPROVAL", "Another administrator must approve your own expense");
            return true;
        }
        return false;
    }

    public static void checkReject(Status s, boolean isAdmin, String reason) {
        if (!isAdmin) throw ApiException.forbidden("ADMIN_REQUIRED", "Only administrators reject expenses");
        if (s != Status.SUBMITTED) throw ApiException.rule("EXPENSE_NOT_PENDING", "Only pending expenses can be rejected");
        checkReason(reason);
    }

    public static void checkVoid(Status s, boolean isAdmin, String reason) {
        if (!isAdmin) throw ApiException.forbidden("ADMIN_REQUIRED", "Only administrators void expenses");
        if (s != Status.APPROVED) throw ApiException.rule("EXPENSE_NOT_APPROVED", "Only approved expenses can be voided");
        checkReason(reason);
    }

    static void checkReason(String reason) {
        if (reason == null || reason.strip().length() < 5) throw ApiException.badRequest("REASON_REQUIRED", "Give a reason (at least 5 characters)");
    }

    /** Budget: team members plan it before approval; once the campaign is live only administrators change it. */
    public static void checkBudgetEdit(Campaign.Status s, boolean isAdmin) {
        if (s == Campaign.Status.ARCHIVED) throw ApiException.rule("CAMPAIGN_ARCHIVED", "This campaign's accounts are closed (archived)");
        if (!isAdmin && (s == Campaign.Status.PUBLISHED || s == Campaign.Status.CLOSED)) {
            throw ApiException.forbidden("ADMIN_REQUIRED", "Once the campaign is approved, only administrators change its budget");
        }
    }

    public static String category(String raw) {
        String c = raw == null ? "" : raw.strip().replaceAll("\\s+", " ");
        if (c.length() < 2 || c.length() > 60) throw ApiException.badRequest("INVALID_CATEGORY", "Category must be 2-60 characters");
        return c;
    }

    /** Indian financial year (April-March) of a date, e.g. 2026-27. */
    public static String financialYear(LocalDate d) {
        int start = d.getMonthValue() >= 4 ? d.getYear() : d.getYear() - 1;
        return start + "-" + String.format("%02d", (start + 1) % 100);
    }

    public static String voucherNo(String fy, int n) { return "EXP/" + fy + "/" + String.format("%04d", n); }

    // ---------------- staff money

    /**
     * NGO's position with one staff member: positive = NGO owes them (reimbursement due), negative = they hold NGO cash.
     * A return cannot exceed the cash held; a reimbursement cannot exceed what is owed.
     */
    public static void checkSettlement(String kind, BigDecimal amount, BigDecimal balance) {
        validateAmount(amount);
        switch (kind) {
            case "ADVANCE" -> { }
            case "RETURN" -> {
                if (balance.signum() >= 0 || amount.compareTo(balance.negate()) > 0) {
                    throw ApiException.rule("RETURN_TOO_LARGE", "The staff member holds only ₹" + plain(balance.negate().max(BigDecimal.ZERO)));
                }
            }
            case "REIMBURSEMENT" -> {
                if (balance.signum() <= 0 || amount.compareTo(balance) > 0) {
                    throw ApiException.rule("REIMBURSEMENT_TOO_LARGE", "The NGO owes this staff member only ₹" + plain(balance.max(BigDecimal.ZERO)));
                }
            }
            default -> throw ApiException.badRequest("INVALID_KIND", "Unknown entry type");
        }
    }

    // ---------------- vendors

    public record VendorInput(String name, String phone, String email, String gstin, String pan, String address,
                              String category, String notes) {}

    /** Normalises (trim, upper-case tax ids, digits-only phone) and validates a vendor. */
    public static VendorInput vendor(VendorInput v) {
        String name = trim(v.name());
        if (name == null || name.length() < 2 || name.length() > 120) throw ApiException.badRequest("INVALID_NAME", "Vendor name must be 2-120 characters");
        String phone = trim(v.phone());
        if (phone != null) {
            phone = phone.replaceAll("[\\s-]", "");
            if (!PHONE.matcher(phone).matches()) throw ApiException.badRequest("INVALID_PHONE", "Enter a 10-digit Indian mobile number");
            if (phone.startsWith("+91")) phone = phone.substring(3);
        }
        String email = trim(v.email());
        if (email != null && (email.length() > 120 || !EMAIL.matcher(email).matches())) throw ApiException.badRequest("INVALID_EMAIL", "Enter a valid e-mail");
        String gstin = upper(v.gstin());
        if (gstin != null && !GSTIN.matcher(gstin).matches()) throw ApiException.badRequest("INVALID_GSTIN", "GSTIN must be 15 characters like 27ABCDE1234F1Z5");
        String pan = upper(v.pan());
        if (pan != null && !PAN.matcher(pan).matches()) throw ApiException.badRequest("INVALID_PAN", "PAN must look like ABCDE1234F");
        return new VendorInput(name, phone, email == null ? null : email.toLowerCase(), gstin, pan, limit(v.address(), 300),
                limit(v.category(), 60), limit(v.notes(), 500));
    }

    static String trim(String s) {
        if (s == null) return null;
        String t = s.strip();
        return t.isEmpty() ? null : t;
    }

    private static String upper(String s) { String t = trim(s); return t == null ? null : t.toUpperCase().replace(" ", ""); }

    private static String limit(String s, int max) {
        String t = trim(s);
        if (t != null && t.length() > max) throw ApiException.badRequest("TEXT_TOO_LONG", "Text is longer than " + max + " characters");
        return t;
    }

    private static String plain(BigDecimal v) { return v.stripTrailingZeros().toPlainString(); }
}
