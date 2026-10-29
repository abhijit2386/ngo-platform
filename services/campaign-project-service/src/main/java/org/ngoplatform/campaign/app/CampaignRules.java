package org.ngoplatform.campaign.app;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;
import org.ngoplatform.campaign.domain.Campaign.Status;
import org.ngoplatform.commons.error.ApiException;

/**
 * Who may do what to a campaign, by status (owner decision 2026-10-29):
 * any team member with CAMPAIGN_MANAGE drafts, edits drafts and submits; only NGO administrators approve
 * (publish), send back, edit a live campaign, close it or archive a closed one.
 */
public final class CampaignRules {
    private CampaignRules() {}

    public static final Set<String> CAUSES = Set.of("EDUCATION", "HEALTH", "WOMEN_EMPOWERMENT", "CHILD_WELFARE",
            "ELDERLY_CARE", "DISABILITY", "ENVIRONMENT", "ANIMAL_WELFARE", "RURAL_DEVELOPMENT", "DISASTER_RELIEF",
            "LIVELIHOOD", "HUNGER_NUTRITION", "WATER_SANITATION", "ARTS_CULTURE", "SPORTS", "OTHER");

    public static final BigDecimal MIN_GOAL = new BigDecimal("1000.00");
    public static final BigDecimal MAX_GOAL = new BigDecimal("100000000.00"); // ₹10 crore
    /** A campaign may run at most a year; open-ended campaigns are not allowed (donors need an end date). */
    public static final int MAX_DAYS = 366;
    public static final BigDecimal MIN_DONATION = new BigDecimal("10.00");
    public static final BigDecimal MAX_TEST_DONATION = new BigDecimal("1000000.00");

    public static void checkEdit(Status s, boolean admin) {
        switch (s) {
            case DRAFT, PENDING_APPROVAL -> { }
            case PUBLISHED -> { if (!admin) throw ApiException.forbidden("ADMIN_ONLY_LIVE_EDIT", "Only an administrator can change a live campaign"); }
            default -> throw ApiException.rule("CAMPAIGN_CLOSED", "Closed campaigns cannot be edited");
        }
    }

    public static void checkSubmit(Status s) {
        if (s != Status.DRAFT) throw ApiException.rule("INVALID_CAMPAIGN_TRANSITION", "Only drafts can be sent for approval");
    }

    public static void checkWithdraw(Status s) {
        if (s != Status.PENDING_APPROVAL) throw ApiException.rule("NOT_PENDING", "Campaign is not awaiting approval");
    }

    /** Admins approve a pending campaign, or publish their own draft directly. */
    public static void checkApprove(Status s, boolean admin) {
        requireAdmin(admin);
        if (s != Status.DRAFT && s != Status.PENDING_APPROVAL) {
            throw ApiException.rule("INVALID_CAMPAIGN_TRANSITION", "Campaign is " + s);
        }
    }

    public static void checkReject(Status s, boolean admin, String reason) {
        requireAdmin(admin);
        if (s != Status.PENDING_APPROVAL) throw ApiException.rule("NOT_PENDING", "Campaign is not awaiting approval");
        if (reason == null || reason.isBlank() || reason.trim().length() < 5) {
            throw ApiException.badRequest("REASON_REQUIRED", "Tell the team what to change (at least 5 characters)");
        }
    }

    public static void checkClose(Status s, boolean admin) {
        requireAdmin(admin);
        if (s != Status.PUBLISHED) throw ApiException.rule("NOT_PUBLISHED", "Only live campaigns can be closed");
    }

    /** Drafts can be discarded by the team; closed campaigns are archived by an administrator. */
    public static void checkArchive(Status s, boolean admin) {
        switch (s) {
            case DRAFT -> { }
            case CLOSED -> requireAdmin(admin);
            case PUBLISHED -> throw ApiException.rule("CLOSE_FIRST", "Close the campaign before archiving");
            case PENDING_APPROVAL -> throw ApiException.rule("WITHDRAW_FIRST", "Withdraw the approval request first");
            default -> throw ApiException.rule("ALREADY_ARCHIVED", "Campaign is already archived");
        }
    }

    public static void validate(String cause, BigDecimal goal, LocalDate start, LocalDate end) {
        if (cause != null && !CAUSES.contains(cause)) throw ApiException.badRequest("INVALID_CAUSE", "Unknown cause");
        if (goal == null || goal.compareTo(MIN_GOAL) < 0 || goal.compareTo(MAX_GOAL) > 0 || goal.stripTrailingZeros().scale() > 2) {
            throw ApiException.badRequest("INVALID_GOAL", "Goal must be between ₹1,000 and ₹10 crore");
        }
        if (start == null || end == null) throw ApiException.badRequest("DATES_REQUIRED", "Start and end dates are required");
        if (end.isBefore(start)) throw ApiException.badRequest("INVALID_DATES", "End date is before start date");
        if (start.plusDays(MAX_DAYS).isBefore(end)) throw ApiException.badRequest("TOO_LONG", "A campaign can run for at most one year");
    }

    /** At submission/approval the campaign must still be able to run. */
    public static void checkRunnable(LocalDate end, LocalDate today) {
        if (end.isBefore(today)) throw ApiException.rule("END_IN_PAST", "End date has passed; change the dates first");
    }

    /** Donations are accepted only while a campaign is live and within its dates. */
    public static void checkAcceptsDonation(Status s, LocalDate start, LocalDate end, LocalDate today, BigDecimal amount) {
        if (s != Status.PUBLISHED) throw ApiException.rule("CAMPAIGN_NOT_LIVE", "Campaign is not accepting donations");
        if (today.isBefore(start)) throw ApiException.rule("CAMPAIGN_NOT_STARTED", "Campaign has not started yet");
        if (end != null && today.isAfter(end)) throw ApiException.rule("CAMPAIGN_ENDED", "Campaign has ended");
        if (amount == null || amount.compareTo(MIN_DONATION) < 0 || amount.compareTo(MAX_TEST_DONATION) > 0
                || amount.stripTrailingZeros().scale() > 2) {
            throw ApiException.badRequest("INVALID_AMOUNT", "Amount must be between ₹10 and ₹10 lakh");
        }
    }

    private static void requireAdmin(boolean admin) {
        if (!admin) throw ApiException.forbidden("ADMIN_APPROVAL_REQUIRED", "Only an NGO administrator can do this");
    }
}
