package org.ngoplatform.campaign.app;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.ngoplatform.campaign.domain.Campaign;
import org.ngoplatform.campaign.domain.Expense;
import org.ngoplatform.campaign.domain.Repos;
import org.ngoplatform.commons.money.Money;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The campaign's account sheet: money in (donations), budget vs actual per category, every approved expense with its
 * voucher, pending and voided items, and the staff cash position for this campaign. Shown in the app and exported as
 * PDF / Excel. Only APPROVED expenses count as spent; it is "final" once the campaign is archived.
 */
@Component
public class AccountSheet {
    public record CampaignInfo(UUID id, String title, String status, LocalDate startDate, LocalDate endDate, BigDecimal goal) {}
    public record Income(BigDecimal online, int onlineCount, BigDecimal test, int testCount, BigDecimal refunded, BigDecimal net) {}
    public record BudgetRow(UUID lineId, String category, BigDecimal planned, BigDecimal spent, BigDecimal remaining, int count,
                            boolean budgeted) {}
    public record Totals(BigDecimal planned, BigDecimal spent, BigDecimal budgetRemaining, BigDecimal balance,
                         BigDecimal pendingAmount, int pendingCount, int approvedCount, int withoutBillCount) {}
    public record ExpenseRow(UUID id, String voucherNo, LocalDate date, String category, String description, String vendor,
                             String billNumber, String mode, String paidBy, BigDecimal amount, String status, String submittedBy,
                             String decidedBy, boolean selfApproved, int bills, String noBillReason, String voidReason) {}
    public record StaffRow(UUID userId, String name, BigDecimal advanced, BigDecimal spent, BigDecimal returned,
                           BigDecimal reimbursed, BigDecimal balance) {}
    public record Sheet(String ngoName, CampaignInfo campaign, Instant generatedAt, String generatedBy, boolean finalSheet,
                        Income income, List<BudgetRow> budget, Totals totals, List<ExpenseRow> expenses,
                        List<ExpenseRow> pending, List<ExpenseRow> voided, List<StaffRow> staff) {}

    private final Repos.Expenses expenses;
    private final AccountsService accounts;
    private final TeamDirectory team;
    private final JdbcTemplate jdbc;

    public AccountSheet(Repos.Expenses expenses, AccountsService accounts, TeamDirectory team, JdbcTemplate jdbc) {
        this.expenses = expenses; this.accounts = accounts; this.team = team; this.jdbc = jdbc;
    }

    public Sheet build(Campaign c, UUID viewer) {
        Income income = jdbc.queryForObject("select "
                        + "coalesce(sum(case when not is_test then amount end), 0), count(*) filter (where not is_test), "
                        + "coalesce(sum(case when is_test then amount end), 0), count(*) filter (where is_test), "
                        + "coalesce(sum(refunded), 0) from campaign_donations where campaign_id = ?",
                (rs, i) -> {
                    BigDecimal online = rs.getBigDecimal(1), test = rs.getBigDecimal(3), refunded = rs.getBigDecimal(5);
                    return new Income(Money.round(online), rs.getInt(2), Money.round(test), rs.getInt(4), Money.round(refunded),
                            Money.round(online.add(test).subtract(refunded)));
                }, c.getId());

        List<Expense> all = expenses.findByCampaignIdOrderByExpenseDateDescCreatedAtDesc(c.getId());
        Map<UUID, Integer> bills = accounts.attachmentCounts(c.getId());
        Set<UUID> people = new HashSet<>();
        all.forEach(e -> { people.add(e.getSubmittedBy()); people.add(e.getDecidedBy()); people.add(e.getPaidByUser()); });
        List<AccountsService.StaffBalance> staff = accounts.balances(c.getNgoId(), c.getId());
        staff.forEach(s -> people.add(s.userId()));
        people.add(viewer);
        people.remove(null);
        Map<UUID, String> names = team.names(people);

        // Budget vs actual: budget lines in order, then unbudgeted categories.
        Map<String, BudgetRow> rows = new LinkedHashMap<>();
        for (AccountsService.BudgetLine b : accounts.budget(c.getId())) {
            rows.put("L" + b.id(), new BudgetRow(b.id(), b.category(), b.planned(), Money.ZERO, b.planned(), 0, true));
        }
        BigDecimal spent = Money.ZERO, pendingAmount = Money.ZERO;
        int pendingCount = 0, approvedCount = 0, noBill = 0;
        List<ExpenseRow> approved = new ArrayList<>(), pending = new ArrayList<>(), voided = new ArrayList<>();
        for (Expense e : all) {
            ExpenseRow row = row(e, names, bills.getOrDefault(e.getId(), 0));
            switch (e.getStatus()) {
                case APPROVED -> {
                    approved.add(row);
                    approvedCount++;
                    spent = spent.add(e.getAmount());
                    if (row.bills() == 0) noBill++;
                    String key = e.getBudgetLineId() != null && rows.containsKey("L" + e.getBudgetLineId())
                            ? "L" + e.getBudgetLineId() : "C" + e.getCategory().toLowerCase();
                    BudgetRow r = rows.get(key);
                    if (r == null) r = new BudgetRow(null, e.getCategory(), Money.ZERO, Money.ZERO, Money.ZERO, 0, false);
                    BigDecimal s = r.spent().add(e.getAmount());
                    rows.put(key, new BudgetRow(r.lineId(), r.category(), r.planned(), s, r.planned().subtract(s), r.count() + 1, r.budgeted()));
                }
                case SUBMITTED -> { pending.add(row); pendingCount++; pendingAmount = pendingAmount.add(e.getAmount()); }
                case VOID -> voided.add(row);
                default -> { } // rejected: with the submitter, not part of the accounts
            }
        }
        approved.sort((a, b) -> a.voucherNo().compareTo(b.voucherNo()));
        BigDecimal planned = rows.values().stream().map(BudgetRow::planned).reduce(Money.ZERO, BigDecimal::add);
        Totals totals = new Totals(planned, spent, planned.subtract(spent), income.net().subtract(spent), pendingAmount, pendingCount,
                approvedCount, noBill);
        List<StaffRow> staffRows = staff.stream().map(s -> new StaffRow(s.userId(), names.get(s.userId()), s.advanced(), s.spent(),
                s.returned(), s.reimbursed(), s.balance())).toList();
        return new Sheet(c.getNgoName(), new CampaignInfo(c.getId(), c.getTitle(), c.getStatus().name(), c.getStartDate(), c.getEndDate(),
                c.getGoalAmount()), Instant.now(), names.get(viewer), c.getStatus() == Campaign.Status.ARCHIVED, income,
                new ArrayList<>(rows.values()), totals, approved, pending, voided, staffRows);
    }

    private static ExpenseRow row(Expense e, Map<UUID, String> names, int bills) {
        String paidBy = "STAFF".equals(e.getPaidBy()) ? names.getOrDefault(e.getPaidByUser(), "Staff") : "NGO";
        return new ExpenseRow(e.getId(), e.getVoucherNo(), e.getExpenseDate(), e.getCategory(), e.getDescription(), e.getVendorName(),
                e.getBillNumber(), e.getPaymentMode(), paidBy, e.getAmount(), e.getStatus().name(), names.get(e.getSubmittedBy()),
                e.getDecidedBy() == null ? null : names.get(e.getDecidedBy()), e.isSelfApproved(), bills, e.getNoBillReason(),
                e.getVoidReason());
    }

    static String modeLabel(String mode) {
        return switch (mode == null ? "" : mode) {
            case "CASH" -> "Cash";
            case "UPI" -> "UPI";
            case "BANK_TRANSFER" -> "Bank transfer";
            case "CHEQUE" -> "Cheque";
            case "CARD" -> "Card";
            default -> "";
        };
    }

    static String statusLabel(String status) {
        return switch (status) {
            case "DRAFT" -> "Draft";
            case "PENDING_APPROVAL" -> "Awaiting approval";
            case "PUBLISHED" -> "Live";
            case "CLOSED" -> "Closed";
            case "ARCHIVED" -> "Archived (final)";
            default -> status;
        };
    }
}
