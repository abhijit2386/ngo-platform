package org.ngoplatform.campaign.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.ngoplatform.campaign.app.CampaignRulesTest.code;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.ngoplatform.campaign.app.AccountRules.Status;
import org.ngoplatform.campaign.domain.Campaign;

class AccountRulesTest {
    static final LocalDate TODAY = LocalDate.of(2026, 10, 29);
    static final LocalDate START = LocalDate.of(2026, 10, 1);

    @Test
    void expenseNeedsPositiveAmountPastDateModeAndPayer() {
        AccountRules.validateExpense(new BigDecimal("450.50"), TODAY, TODAY, START, "CASH", "NGO");
        assertThat(code(() -> AccountRules.validateExpense(BigDecimal.ZERO, TODAY, TODAY, START, "CASH", "NGO"))).isEqualTo("INVALID_AMOUNT");
        assertThat(code(() -> AccountRules.validateExpense(new BigDecimal("1.234"), TODAY, TODAY, START, "CASH", "NGO"))).isEqualTo("INVALID_AMOUNT");
        assertThat(code(() -> AccountRules.validateExpense(new BigDecimal("10000000.01"), TODAY, TODAY, START, "CASH", "NGO"))).isEqualTo("AMOUNT_TOO_LARGE");
        assertThat(code(() -> AccountRules.validateExpense(BigDecimal.TEN, TODAY.plusDays(1), TODAY, START, "CASH", "NGO"))).isEqualTo("DATE_IN_FUTURE");
        assertThat(code(() -> AccountRules.validateExpense(BigDecimal.TEN, START.minusDays(91), TODAY, START, "CASH", "NGO"))).isEqualTo("DATE_TOO_EARLY");
        AccountRules.validateExpense(BigDecimal.TEN, START.minusDays(90), TODAY, START, "UPI", "STAFF");
        assertThat(code(() -> AccountRules.validateExpense(BigDecimal.TEN, TODAY, TODAY, START, "BITCOIN", "NGO"))).isEqualTo("INVALID_MODE");
        assertThat(code(() -> AccountRules.validateExpense(BigDecimal.TEN, TODAY, TODAY, START, "CASH", "ME"))).isEqualTo("INVALID_PAID_BY");
    }

    @Test
    void onlyLiveOrClosedCampaignsTakeExpenses() {
        AccountRules.checkCampaignOpenForExpenses(Campaign.Status.PUBLISHED);
        AccountRules.checkCampaignOpenForExpenses(Campaign.Status.CLOSED);
        assertThat(code(() -> AccountRules.checkCampaignOpenForExpenses(Campaign.Status.DRAFT))).isEqualTo("CAMPAIGN_NOT_LIVE");
        assertThat(code(() -> AccountRules.checkCampaignOpenForExpenses(Campaign.Status.ARCHIVED))).isEqualTo("CAMPAIGN_ARCHIVED");
    }

    @Test
    void billOrReasonRequired() {
        AccountRules.checkEvidence(1, null);
        AccountRules.checkEvidence(0, "Auto-rickshaw fare");
        assertThat(code(() -> AccountRules.checkEvidence(0, " "))).isEqualTo("BILL_REQUIRED");
    }

    @Test
    void approvalByAnotherAdminOrSoleAdmin() {
        assertThat(AccountRules.checkApprove(Status.SUBMITTED, true, false, 2)).isFalse();
        assertThat(code(() -> AccountRules.checkApprove(Status.SUBMITTED, false, false, 2))).isEqualTo("ADMIN_REQUIRED");
        assertThat(code(() -> AccountRules.checkApprove(Status.SUBMITTED, true, true, 2))).isEqualTo("SELF_APPROVAL");
        assertThat(AccountRules.checkApprove(Status.SUBMITTED, true, true, 1)).isTrue();
        assertThat(code(() -> AccountRules.checkApprove(Status.APPROVED, true, false, 2))).isEqualTo("EXPENSE_NOT_PENDING");
        assertThat(code(() -> AccountRules.checkReject(Status.SUBMITTED, true, "no"))).isEqualTo("REASON_REQUIRED");
        assertThat(code(() -> AccountRules.checkVoid(Status.SUBMITTED, true, "Entered twice"))).isEqualTo("EXPENSE_NOT_APPROVED");
    }

    @Test
    void editAndWithdrawOnlyBeforeApproval() {
        AccountRules.checkEdit(Status.REJECTED, true, false);
        AccountRules.checkEdit(Status.SUBMITTED, false, true);
        assertThat(code(() -> AccountRules.checkEdit(Status.SUBMITTED, false, false))).isEqualTo("NOT_YOUR_EXPENSE");
        assertThat(code(() -> AccountRules.checkEdit(Status.APPROVED, true, true))).isEqualTo("EXPENSE_LOCKED");
        assertThat(code(() -> AccountRules.checkWithdraw(Status.APPROVED, true))).isEqualTo("EXPENSE_LOCKED");
        assertThat(code(() -> AccountRules.checkWithdraw(Status.SUBMITTED, false))).isEqualTo("NOT_YOUR_EXPENSE");
    }

    @Test
    void budgetBelongsToAdminsOnceLive() {
        AccountRules.checkBudgetEdit(Campaign.Status.DRAFT, false);
        AccountRules.checkBudgetEdit(Campaign.Status.PUBLISHED, true);
        assertThat(code(() -> AccountRules.checkBudgetEdit(Campaign.Status.PUBLISHED, false))).isEqualTo("ADMIN_REQUIRED");
        assertThat(code(() -> AccountRules.checkBudgetEdit(Campaign.Status.ARCHIVED, true))).isEqualTo("CAMPAIGN_ARCHIVED");
    }

    @Test
    void voucherNumbersFollowIndianFinancialYear() {
        assertThat(AccountRules.financialYear(LocalDate.of(2026, 4, 1))).isEqualTo("2026-27");
        assertThat(AccountRules.financialYear(LocalDate.of(2027, 3, 31))).isEqualTo("2026-27");
        assertThat(AccountRules.financialYear(LocalDate.of(2099, 12, 1))).isEqualTo("2099-00");
        assertThat(AccountRules.voucherNo("2026-27", 7)).isEqualTo("EXP/2026-27/0007");
    }

    @Test
    void staffSettlementsCannotOvershoot() {
        // Holds ₹2,000 of NGO cash
        AccountRules.checkSettlement("RETURN", new BigDecimal("2000"), new BigDecimal("-2000"));
        assertThat(code(() -> AccountRules.checkSettlement("RETURN", new BigDecimal("2000.01"), new BigDecimal("-2000")))).isEqualTo("RETURN_TOO_LARGE");
        assertThat(code(() -> AccountRules.checkSettlement("REIMBURSEMENT", BigDecimal.ONE, new BigDecimal("-2000")))).isEqualTo("REIMBURSEMENT_TOO_LARGE");
        // NGO owes ₹350
        AccountRules.checkSettlement("REIMBURSEMENT", new BigDecimal("350"), new BigDecimal("350"));
        assertThat(code(() -> AccountRules.checkSettlement("RETURN", BigDecimal.ONE, new BigDecimal("350")))).isEqualTo("RETURN_TOO_LARGE");
        AccountRules.checkSettlement("ADVANCE", new BigDecimal("5000"), new BigDecimal("350"));
    }

    @Test
    void vendorIsNormalisedAndValidated() {
        var v = AccountRules.vendor(new AccountRules.VendorInput("  Sharma Stationers ", "+91 98765-43210", "Sharma@Example.com",
                "27abcde1234f1z5", "abcde 1234f", null, "Stationery", null));
        assertThat(v.name()).isEqualTo("Sharma Stationers");
        assertThat(v.phone()).isEqualTo("9876543210");
        assertThat(v.email()).isEqualTo("sharma@example.com");
        assertThat(v.gstin()).isEqualTo("27ABCDE1234F1Z5");
        assertThat(v.pan()).isEqualTo("ABCDE1234F");
        assertThat(code(() -> AccountRules.vendor(new AccountRules.VendorInput("X", null, null, null, null, null, null, null)))).isEqualTo("INVALID_NAME");
        assertThat(code(() -> AccountRules.vendor(new AccountRules.VendorInput("Ok Name", "12345", null, null, null, null, null, null)))).isEqualTo("INVALID_PHONE");
        assertThat(code(() -> AccountRules.vendor(new AccountRules.VendorInput("Ok Name", null, null, "27ABCDE1234F1Y5", null, null, null, null)))).isEqualTo("INVALID_GSTIN");
    }

    @Test
    void csvHandlesQuotesSemicolonsAndFormulaInjection() {
        List<List<String>> rows = Csv.parse("name,address\r\n\"Rao, Sons\",\"12 \"\"A\"\" Road\"\nLast,\n");
        assertThat(rows).hasSize(3);
        assertThat(rows.get(1)).containsExactly("Rao, Sons", "12 \"A\" Road");
        assertThat(rows.get(2)).containsExactly("Last", "");
        assertThat(Csv.parse("name;phone\nA;9876543210").get(1)).containsExactly("A", "9876543210");
        assertThat(Csv.field("=HYPERLINK(1)")).isEqualTo("'=HYPERLINK(1)");
        assertThat(Csv.field("a,b")).isEqualTo("\"a,b\"");
    }
}
