package org.ngoplatform.campaign.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.ngoplatform.campaign.domain.Campaign.Status;
import org.ngoplatform.commons.error.ApiException;

class CampaignRulesTest {
    static final LocalDate TODAY = LocalDate.of(2026, 10, 29);

    /** Error code thrown by the rule, or null when it passes. */
    static String code(Runnable r) {
        try {
            r.run();
            return null;
        } catch (ApiException e) {
            return e.code();
        }
    }

    @Test
    void teamEditsDraftsAndPendingButOnlyAdminsEditLive() {
        assertThatCode(() -> CampaignRules.checkEdit(Status.DRAFT, false)).doesNotThrowAnyException();
        assertThatCode(() -> CampaignRules.checkEdit(Status.PENDING_APPROVAL, false)).doesNotThrowAnyException();
        assertThat(code(() -> CampaignRules.checkEdit(Status.PUBLISHED, false))).isEqualTo("ADMIN_ONLY_LIVE_EDIT");
        assertThatCode(() -> CampaignRules.checkEdit(Status.PUBLISHED, true)).doesNotThrowAnyException();
        assertThat(code(() -> CampaignRules.checkEdit(Status.CLOSED, true))).isEqualTo("CAMPAIGN_CLOSED");
        assertThat(code(() -> CampaignRules.checkEdit(Status.ARCHIVED, true))).isEqualTo("CAMPAIGN_CLOSED");
    }

    @Test
    void onlyDraftsAreSubmitted() {
        assertThatCode(() -> CampaignRules.checkSubmit(Status.DRAFT)).doesNotThrowAnyException();
        for (Status s : new Status[] {Status.PENDING_APPROVAL, Status.PUBLISHED, Status.CLOSED, Status.ARCHIVED}) {
            assertThat(code(() -> CampaignRules.checkSubmit(s))).isEqualTo("INVALID_CAMPAIGN_TRANSITION");
        }
    }

    @Test
    void staffCannotApproveRejectOrClose() {
        assertThat(code(() -> CampaignRules.checkApprove(Status.PENDING_APPROVAL, false))).isEqualTo("ADMIN_APPROVAL_REQUIRED");
        assertThat(code(() -> CampaignRules.checkReject(Status.PENDING_APPROVAL, false, "Add photos"))).isEqualTo("ADMIN_APPROVAL_REQUIRED");
        assertThat(code(() -> CampaignRules.checkClose(Status.PUBLISHED, false))).isEqualTo("ADMIN_APPROVAL_REQUIRED");
    }

    @Test
    void adminsApprovePendingOrTheirOwnDraft() {
        assertThatCode(() -> CampaignRules.checkApprove(Status.PENDING_APPROVAL, true)).doesNotThrowAnyException();
        assertThatCode(() -> CampaignRules.checkApprove(Status.DRAFT, true)).doesNotThrowAnyException();
        assertThat(code(() -> CampaignRules.checkApprove(Status.PUBLISHED, true))).isEqualTo("INVALID_CAMPAIGN_TRANSITION");
        assertThat(code(() -> CampaignRules.checkApprove(Status.CLOSED, true))).isEqualTo("INVALID_CAMPAIGN_TRANSITION");
    }

    @Test
    void rejectionNeedsPendingAndAReason() {
        assertThat(code(() -> CampaignRules.checkReject(Status.DRAFT, true, "Add photos"))).isEqualTo("NOT_PENDING");
        assertThat(code(() -> CampaignRules.checkReject(Status.PENDING_APPROVAL, true, " "))).isEqualTo("REASON_REQUIRED");
        assertThat(code(() -> CampaignRules.checkReject(Status.PENDING_APPROVAL, true, "no"))).isEqualTo("REASON_REQUIRED");
        assertThatCode(() -> CampaignRules.checkReject(Status.PENDING_APPROVAL, true, "Add a cover photo")).doesNotThrowAnyException();
    }

    @Test
    void archiveRules() {
        assertThatCode(() -> CampaignRules.checkArchive(Status.DRAFT, false)).doesNotThrowAnyException();
        assertThat(code(() -> CampaignRules.checkArchive(Status.CLOSED, false))).isEqualTo("ADMIN_APPROVAL_REQUIRED");
        assertThatCode(() -> CampaignRules.checkArchive(Status.CLOSED, true)).doesNotThrowAnyException();
        assertThat(code(() -> CampaignRules.checkArchive(Status.PUBLISHED, true))).isEqualTo("CLOSE_FIRST");
        assertThat(code(() -> CampaignRules.checkArchive(Status.PENDING_APPROVAL, true))).isEqualTo("WITHDRAW_FIRST");
        assertThat(code(() -> CampaignRules.checkArchive(Status.ARCHIVED, true))).isEqualTo("ALREADY_ARCHIVED");
    }

    @Test
    void withdrawOnlyWhilePending() {
        assertThatCode(() -> CampaignRules.checkWithdraw(Status.PENDING_APPROVAL)).doesNotThrowAnyException();
        assertThat(code(() -> CampaignRules.checkWithdraw(Status.DRAFT))).isEqualTo("NOT_PENDING");
    }

    @Test
    void validatesGoalCauseAndDates() {
        LocalDate s = TODAY, e = TODAY.plusDays(30);
        assertThatCode(() -> CampaignRules.validate("EDUCATION", new BigDecimal("50000"), s, e)).doesNotThrowAnyException();
        assertThatCode(() -> CampaignRules.validate(null, new BigDecimal("1000.50"), s, e)).doesNotThrowAnyException();
        assertThat(code(() -> CampaignRules.validate("PARTY", new BigDecimal("50000"), s, e))).isEqualTo("INVALID_CAUSE");
        assertThat(code(() -> CampaignRules.validate(null, new BigDecimal("999.99"), s, e))).isEqualTo("INVALID_GOAL");
        assertThat(code(() -> CampaignRules.validate(null, new BigDecimal("100000000.01"), s, e))).isEqualTo("INVALID_GOAL");
        assertThat(code(() -> CampaignRules.validate(null, new BigDecimal("5000.001"), s, e))).isEqualTo("INVALID_GOAL");
        assertThat(code(() -> CampaignRules.validate(null, new BigDecimal("5000"), s, null))).isEqualTo("DATES_REQUIRED");
        assertThat(code(() -> CampaignRules.validate(null, new BigDecimal("5000"), e, s))).isEqualTo("INVALID_DATES");
        assertThat(code(() -> CampaignRules.validate(null, new BigDecimal("5000"), s, s.plusDays(367)))).isEqualTo("TOO_LONG");
        assertThatCode(() -> CampaignRules.validate(null, new BigDecimal("5000"), s, s.plusDays(366))).doesNotThrowAnyException();
    }

    @Test
    void endedCampaignsCannotBeSubmitted() {
        assertThat(code(() -> CampaignRules.checkRunnable(TODAY.minusDays(1), TODAY))).isEqualTo("END_IN_PAST");
        assertThatCode(() -> CampaignRules.checkRunnable(TODAY, TODAY)).doesNotThrowAnyException();
    }

    @Test
    void donationsOnlyWhileLiveAndWithinDates() {
        BigDecimal ok = new BigDecimal("500");
        assertThatCode(() -> CampaignRules.checkAcceptsDonation(Status.PUBLISHED, TODAY, TODAY, TODAY, ok)).doesNotThrowAnyException();
        assertThat(code(() -> CampaignRules.checkAcceptsDonation(Status.DRAFT, TODAY, TODAY, TODAY, ok))).isEqualTo("CAMPAIGN_NOT_LIVE");
        assertThat(code(() -> CampaignRules.checkAcceptsDonation(Status.CLOSED, TODAY, TODAY, TODAY, ok))).isEqualTo("CAMPAIGN_NOT_LIVE");
        assertThat(code(() -> CampaignRules.checkAcceptsDonation(Status.PUBLISHED, TODAY.plusDays(1), TODAY.plusDays(5), TODAY, ok)))
                .isEqualTo("CAMPAIGN_NOT_STARTED");
        assertThat(code(() -> CampaignRules.checkAcceptsDonation(Status.PUBLISHED, TODAY.minusDays(5), TODAY.minusDays(1), TODAY, ok)))
                .isEqualTo("CAMPAIGN_ENDED");
        assertThat(code(() -> CampaignRules.checkAcceptsDonation(Status.PUBLISHED, TODAY, TODAY, TODAY, new BigDecimal("9.99"))))
                .isEqualTo("INVALID_AMOUNT");
        assertThat(code(() -> CampaignRules.checkAcceptsDonation(Status.PUBLISHED, TODAY, TODAY, TODAY, new BigDecimal("10.005"))))
                .isEqualTo("INVALID_AMOUNT");
    }

    @Test
    void slugsAreAsciiAndBounded() {
        assertThat(CampaignService.slugify("Books for 200 Children!")).isEqualTo("books-for-200-children");
        assertThat(CampaignService.slugify("Café  Médical -- Camp")).isEqualTo("cafe-medical-camp");
        assertThat(CampaignService.slugify("शिक्षा")).isEqualTo("campaign");
        assertThat(CampaignService.slugify("x".repeat(300))).hasSize(100);
    }
}
